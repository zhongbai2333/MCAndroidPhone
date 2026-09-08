"""Small protobuf wire-compatible view of the AOSP EmulatorController API.

Field numbers and RPC names checked against the Android SDK emulator's official
emulator_controller.proto. Unknown fields are retained/ignored by protobuf.
No generated code or runtime compiler is required. See bridge/README.md sources.
"""
from google.protobuf import descriptor_pb2, descriptor_pool, empty_pb2, message_factory


def _build():
    schema = descriptor_pb2.FileDescriptorProto(
        name="mcandroid_emulator_subset.proto", package="android.emulation.control", syntax="proto3"
    )
    # Integer enum fields share the same varint encoding as upstream enums.
    definitions = {
        "ImageFormat": [("format", 1, 5), ("width", 3, 13), ("height", 4, 13), ("display", 5, 13)],
        "Image": [("format", 1, 11, "ImageFormat"), ("width", 2, 13), ("height", 3, 13),
                  ("image", 4, 12), ("seq", 5, 13), ("timestampUs", 6, 4)],
        "Touch": [("x", 1, 5), ("y", 2, 5), ("identifier", 3, 5), ("pressure", 4, 5)],
        "TouchEvent": [("touches", 1, 11, "Touch", True), ("display", 2, 5)],
        "KeyboardEvent": [("codeType", 1, 5), ("eventType", 2, 5), ("keyCode", 3, 5),
                          ("key", 4, 9), ("text", 5, 9)],
    }
    for name, fields in definitions.items():
        message = schema.message_type.add(name=name)
        for spec in fields:
            field = message.field.add(name=spec[0], number=spec[1], type=spec[2],
                                      label=3 if len(spec) > 4 and spec[4] else 1)
            if len(spec) > 3:
                field.type_name = ".android.emulation.control." + spec[3]
    pool = descriptor_pool.DescriptorPool()
    pool.Add(schema)
    return {name: message_factory.GetMessageClass(pool.FindMessageTypeByName("android.emulation.control." + name))
            for name in definitions}


globals().update(_build())
Empty = empty_pb2.Empty
SERVICE = "/android.emulation.control.EmulatorController/"


class Controller:
    def __init__(self, channel):
        self.getScreenshot = channel.unary_unary(SERVICE + "getScreenshot", request_serializer=ImageFormat.SerializeToString,
                                                 response_deserializer=Image.FromString)
        self.streamScreenshot = channel.unary_stream(SERVICE + "streamScreenshot", request_serializer=ImageFormat.SerializeToString,
                                                     response_deserializer=Image.FromString)
        self.sendTouch = channel.unary_unary(SERVICE + "sendTouch", request_serializer=TouchEvent.SerializeToString,
                                             response_deserializer=Empty.FromString)
        self.sendKey = channel.unary_unary(SERVICE + "sendKey", request_serializer=KeyboardEvent.SerializeToString,
                                           response_deserializer=Empty.FromString)
