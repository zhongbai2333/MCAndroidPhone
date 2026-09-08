"""D3D11 texture leases. Only handles/metadata cross Python and TCP, never pixels.

QEMU owns KeyedMutex(0) except during UpdateTexture2d. Its D-Bus reply is held
until the consumer releases that mutex and ACKs. Scanout alone is NOT a lease.
Each authenticated connection gets a fresh QEMU listener, including an initial
update for a static desktop. BIOS/system-memory frames are deliberately skipped.
"""
from __future__ import annotations
import ctypes
import os
from pathlib import Path
import secrets
import socket
import threading
import time
import uuid
from .protocol import ProtocolError, Session, lines
from .qemu_dbus import QemuDbusBackend, DISPLAY, MAP_INTERFACE, LISTENER_PATH
from .dbus_transport import Variant

D3D_INTERFACE = DISPLAY + 'Listener.Win32.D3d11'

def close_handle(handle):
    if handle:
        close = ctypes.WinDLL('kernel32', use_last_error=True).CloseHandle
        close.argtypes = [ctypes.c_void_p]
        close.restype = ctypes.c_int
        close(handle)

def validate_scanout(body):
    if len(body) != 8:
        raise ValueError('Invalid D3D11 scanout')
    handle, tw, th, top, x, y, w, h = body
    if (any(type(v) is not int for v in (handle,tw,th,x,y,w,h)) or type(top) is not bool or
        not 0 < handle < (1 << 63) or not 2 <= tw <= 4096 or not 2 <= th <= 4096 or
        min(x,y) < 0 or min(w,h) < 2 or x+w > tw or y+h > th):
        raise ValueError('Invalid D3D11 texture bounds/handle')
    return body

class GpuBackend(QemuDbusBackend):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.scanout = None
        self.gpu_frames = 0

    def _properties(self):
        return {'Interfaces': Variant('as', [D3D_INTERFACE, MAP_INTERFACE])}

    def _listener_message(self, message):
        try:
            return self._gpu_listener_message(message)
        except Exception as error:
            self.error = error
            raise

    def _gpu_listener_message(self, message):
        if message.path != LISTENER_PATH:
            return super()._listener_message(message)
        interface, member, body = message.interface, message.member, message.body
        if interface == D3D_INTERFACE and member == 'ScanoutTexture2d':
            try:
                validated = validate_scanout(body)
            except Exception:
                if body and type(body[0]) is int: close_handle(body[0])
                raise
            old, self.scanout = self.scanout, validated
            if (self.width, self.height) != tuple(body[6:8]):
                self._release_touch(best_effort=True)
            self.width, self.height = body[6:8]
            if old: close_handle(old[0])
            if self.gpu_frames == 0:
                print(f'QEMU GPU D3D11: {self.width}x{self.height}; CPU pixel copies=0', flush=True)
            return '', ()
        if interface == D3D_INTERFACE and member == 'UpdateTexture2d':
            scanout = self.scanout
            if scanout is None or len(body) != 4:
                raise ValueError('GPU update without scanout')
            x,y,w,h = body
            if (any(type(v) is not int for v in body) or min(x,y,w,h) < 0 or
                x+w > scanout[6] or y+h > scanout[7]):
                raise ValueError('GPU update outside scanout')
            self.gpu_frames += 1
            self.first_frame.set()
            # No application locks here: the socket reader must be able to ACK.
            self.publish(scanout, time.monotonic_ns())
            return '', ()
        if interface == MAP_INTERFACE and member == 'ScanoutMap':
            close_handle(body[0])
            return '', ()
        if (interface == MAP_INTERFACE and member == 'UpdateMap' or
            interface == DISPLAY+'Listener' and member in ('Scanout','Update')):
            return '', ()
        if interface == DISPLAY+'Listener' and member == 'Disable':
            old, self.scanout = self.scanout, None
            if old: close_handle(old[0])
            return '', ()
        return super()._listener_message(message)

    def stop(self):
        super().stop()
        # Peer.stop joins its handler before any retained source handle is closed.
        old, self.scanout = self.scanout, None
        if old: close_handle(old[0])

class GpuSession:
    def __init__(self, sock, backend):
        self.sock, self.backend = sock, backend
        self.lock = threading.RLock()
        self.condition = threading.Condition(self.lock)
        self.closed = False
        self.touching, self.last_touch = False, (0.,0.)
        self.sequence, self.pending = 0, None

    send = Session.send

    def publish(self, scanout, timestamp):
        with self.condition:
            if self.closed: return False
            if self.pending is not None: raise RuntimeError('More than one GPU lease')
            self.sequence += 1
            self.pending = self.sequence
            handle,tw,th,top,x,y,w,h = scanout
            try:
                self.send(f'GPUFRAME\t{self.sequence}\t{handle}\t{tw}\t{th}\t{int(top)}\t{x}\t{y}\t{w}\t{h}\t{timestamp}')
                if not self.condition.wait_for(lambda: self.pending is None or self.closed, timeout=5):
                    raise TimeoutError('GPU lease ACK timed out')
            except Exception:
                self.retire()
                return False
            return not self.closed

    def command(self, fields):
        with self.condition:
            if self.closed: raise ProtocolError('session_retired')
            if len(fields) == 2 and fields[0] == 'GPUACK':
                if int(fields[1]) != self.pending or self.pending is None:
                    raise ProtocolError('stale_gpu_ack')
                self.pending = None
                self.condition.notify_all()
            else:
                # Reuse v1's validated touch/text/key state machine; never its mmap ACK.
                if fields[0] == 'ACK': raise ProtocolError('invalid_gpu_command')
                Session._command(self, fields)

    def retire(self):
        with self.condition:
            self.closed = True
            self.condition.notify_all()
            try: self.sock.shutdown(socket.SHUT_RDWR)
            except OSError: pass

    def close(self):
        self.retire()
        with self.lock:
            if self.touching:
                try: self.backend.touch('UP', *self.last_touch)
                except Exception: pass
                self.touching = False

class GpuServer:
    def __init__(self, runtime, factory, port=18765):
        self.runtime = Path(runtime).resolve()
        self.runtime.mkdir(parents=True, exist_ok=True)
        self.factory = factory
        self.token = secrets.token_urlsafe(32)
        self.stop_event = threading.Event()
        self.listener = socket.socket()
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        self.listener.bind(('127.0.0.1', port))
        self.listener.listen(2)
        self.listener.settimeout(.25)
        self.port = self.listener.getsockname()[1]
        self.config = self.runtime/'bridge.properties'
        temporary = self.runtime/f'gpu-{uuid.uuid4().hex}.tmp'
        temporary.write_text(f'host=127.0.0.1\nport={self.port}\ntoken={self.token}\ntransport=d3d11\n', encoding='ascii')
        os.replace(temporary, self.config)

    def serve_forever(self):
        try:
            while not self.stop_event.is_set():
                try: sock, _ = self.listener.accept()
                except socket.timeout: continue
                self._client(sock)
        finally:
            self.listener.close()
            try:
                if f'token={self.token}\n' in self.config.read_text(): self.config.unlink()
            except OSError: pass

    def _client(self, sock):
        backend = session = None
        try:
            sock.settimeout(.5)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            commands = lines(sock, self.stop_event, time.monotonic()+5)
            hello = next(commands, None)
            if (hello is None or len(hello)!=3 or hello[:2]!=['HELLO','2'] or
                not secrets.compare_digest(hello[2], self.token)):
                raise ProtocolError('authentication_failed')
            backend = self.factory()
            session = GpuSession(sock, backend)
            session.send(f'WELCOME\t2\t{os.getpid()}\tD3D11')
            backend.start(session.publish)
            def health():
                while not backend.stop_event.wait(.25):
                    if session.closed: return
                    try: backend.check_health()
                    except Exception:
                        session.retire()
                        return
            watcher = threading.Thread(target=health, name='gpu-health', daemon=True)
            watcher.start()
            for command in commands:
                backend.check_health()
                session.command(command)
        except Exception as error:
            print(f'GPU client ended: {type(error).__name__}', flush=True)
        finally:
            if session: session.close()
            if backend: backend.stop()
            sock.close()
