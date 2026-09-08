; MCAndroidPhone native VNC/keyboard smoke fixture; NASM -f bin.
; Boot a colored VGA canvas. 'c' changes color; 'r' toggles graphics/text.
; No operating system, guest network, disk writes, or Android dependencies.
bits 16
org 0x7c00

    cli
    xor ax, ax
    mov ds, ax
    mov ss, ax
    mov sp, 0x7c00
    sti
    cld
    call show_mode

read_key:
    xor ax, ax
    int 0x16
    cmp al, 'r'
    je resize
    cmp al, 'c'
    jne read_key
    xor byte [color], 6
    call show_mode
    jmp read_key

resize:
    xor byte [graphics], 1
    call show_mode
    jmp read_key

show_mode:
    cmp byte [graphics], 1
    jne text_mode
    mov ax, 0x0013
    int 0x10
    mov ax, 0xa000
    mov es, ax
    xor di, di
    mov cx, 32000
    mov al, [color]
    mov ah, al
    rep stosw
    ret

text_mode:
    mov ax, 0x0003
    int 0x10
    mov ax, 0xb800
    mov es, ax
    xor di, di
    mov cx, 2000
    mov ax, 0x1f58
    rep stosw
    ret

graphics: db 1
color: db 4

times 510 - ($ - $$) db 0
dw 0xaa55
