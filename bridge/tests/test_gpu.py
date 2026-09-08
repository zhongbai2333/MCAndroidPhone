import socket
import threading
import time
import unittest
from types import SimpleNamespace
from unittest import mock
from mcandroid_bridge import gpu

SCANOUT = (42,1080,1920,False,0,0,1080,1920)

class GpuTests(unittest.TestCase):
    def test_scanout_bounds_and_opaque_handles(self):
        self.assertEqual(SCANOUT, gpu.validate_scanout(SCANOUT))
        for index,value in ((0,0),(0,-1),(1,4097),(2,0),(3,1),(4,-1),(5,1920),(6,1081),(7,1921)):
            invalid=list(SCANOUT);invalid[index]=value
            with self.subTest(index=index),self.assertRaises(ValueError):gpu.validate_scanout(tuple(invalid))

    def test_update_reply_waits_for_ack_without_pixel_mapping(self):
        left,right=socket.socketpair()
        session=gpu.GpuSession(left,mock.Mock())
        result=[]
        thread=threading.Thread(target=lambda:result.append(session.publish(SCANOUT,123)))
        try:
            thread.start();right.settimeout(1)
            message=right.recv(4096).decode()
            self.assertEqual('GPUFRAME\t1\t42\t1080\t1920\t0\t0\t0\t1080\t1920\t123\n',message)
            self.assertTrue(thread.is_alive())
            session.command(['GPUACK','1']);thread.join(1)
            self.assertEqual([True],result)
            with self.assertRaises(gpu.ProtocolError):session.command(['GPUACK','1'])
            self.assertFalse(hasattr(session,'mapping'))
        finally:session.close();thread.join(1);left.close();right.close()

    def test_retirement_wakes_lease_and_releases_touch(self):
        left,right=socket.socketpair();backend=mock.Mock();session=gpu.GpuSession(left,backend)
        result=[];thread=threading.Thread(target=lambda:result.append(session.publish(SCANOUT,123)))
        try:
            session.command(['TOUCH','DOWN','.5','.5'])
            thread.start();right.settimeout(1);right.recv(4096)
            session.close();thread.join(1)
            self.assertEqual([False],result)
            backend.touch.assert_any_call('UP',.5,.5)
        finally:session.close();thread.join(1);left.close();right.close()

    def test_only_update_grants_lease_and_scanout_retires_handle(self):
        with mock.patch.object(gpu.os,'name','nt'),mock.patch.object(gpu,'close_handle') as close:
            backend=gpu.GpuBackend(1080,1920,30,123,1234)
            backend.publish=mock.Mock()
            def call(member,body):
                return backend._listener_message(SimpleNamespace(path=gpu.LISTENER_PATH,interface=gpu.D3D_INTERFACE,member=member,body=body))
            call('ScanoutTexture2d',SCANOUT)
            backend.publish.assert_not_called()
            call('UpdateTexture2d',(0,0,1080,1920))
            backend.publish.assert_called_once()
            call('ScanoutTexture2d',(43,*SCANOUT[1:]))
            close.assert_called_once_with(42)
            with self.assertRaises(ValueError):call('UpdateTexture2d',(0,0,1081,1920))
            self.assertIsNotNone(backend.error)
            backend.stop();close.assert_any_call(43)

if __name__=='__main__':unittest.main()
