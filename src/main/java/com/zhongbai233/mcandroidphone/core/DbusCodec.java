package com.zhongbai233.mcandroidphone.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded peer D-Bus wire codec. No bus discovery or public network authentication. */
final class DbusCodec {
    static final int MAX_BODY=64*1024*1024+4096,MAX_HEADER=65536;
    record Variant(String signature,Object value){}
    record Message(int kind,int flags,long serial,Map<Integer,Object> headers,List<Object> body) {
        String path(){return (String)headers.getOrDefault(1,"");}
        String iface(){return (String)headers.getOrDefault(2,"");}
        String member(){return (String)headers.getOrDefault(3,"");}
    }
    record Type(char code,List<Type> children) {int alignment(){return "ygv".indexOf(code)>=0?1:"nq".indexOf(code)>=0?2:"biuhsoa".indexOf(code)>=0?4:8;}}
    private static final class Signature {
        final String text;int at;
        Signature(String text)throws IOException{if(text.length()>255)throw new IOException("D-Bus signature limit");this.text=text;}
        Type one(int depth)throws IOException {
            if(depth>32||at>=text.length())throw new IOException("Invalid D-Bus signature");char c=text.charAt(at++);
            if("ybnqiuxtdhsogv".indexOf(c)>=0)return new Type(c,List.of());
            if(c=='a')return new Type(c,List.of(one(depth+1)));
            if(c=='('||c=='{'){var children=new ArrayList<Type>();char end=c=='('?')':'}';while(at<text.length()&&text.charAt(at)!=end)children.add(one(depth+1));
                if(at==text.length()||children.isEmpty()||(c=='{'&&children.size()!=2))throw new IOException("Invalid D-Bus struct");at++;return new Type(c,children);}
            throw new IOException("Unsupported D-Bus type");
        }
    }
    static List<Type> types(String signature)throws IOException {var s=new Signature(signature);var result=new ArrayList<Type>();while(s.at<s.text.length())result.add(s.one(0));return result;}
    private static Type scalar(char code){return new Type(code,List.of());}
    private static final class Writer {
        byte[] bytes=new byte[256];int at;final int base;
        Writer(int base){this.base=base;}
        void reserve(int n)throws IOException {if(n<0||(long)at+n>MAX_BODY+MAX_HEADER+16)throw new IOException("D-Bus encoded data limit");if(at+n>bytes.length)bytes=Arrays.copyOf(bytes,Math.max(at+n,Math.min(MAX_BODY+MAX_HEADER+16,bytes.length*2)));}
        void number(long v,int size)throws IOException{reserve(size);for(int i=0;i<size;i++)bytes[at++]=(byte)(v>>>(i*8));}
        void raw(byte[] data)throws IOException{reserve(data.length);System.arraycopy(data,0,bytes,at,data.length);at+=data.length;}
        void align(int n)throws IOException{int pad=(-(base+at))&(n-1);reserve(pad);for(int i=0;i<pad;i++)bytes[at++]=0;}
        void value(Type t,Object v,int depth)throws IOException {
            if(depth>64)throw new IOException("D-Bus value depth limit");align(t.alignment());char c=t.code;
            if("ybnqiuxtdh".indexOf(c)>=0){long n=c=='b'?(Boolean.TRUE.equals(v)?1:0):c=='d'?Double.doubleToRawLongBits(((Number)v).doubleValue()):((Number)v).longValue();number(n,c=='y'?1:"nq".indexOf(c)>=0?2:"biuh".indexOf(c)>=0?4:8);}
            else if("sog".indexOf(c)>=0){String s=(String)v;if(s.indexOf(0)>=0)throw new IOException("D-Bus NUL string");byte[] raw=s.getBytes(c=='g'?StandardCharsets.US_ASCII:StandardCharsets.UTF_8);if(c=='g'&&(raw.length>255||!s.equals(new String(raw,StandardCharsets.US_ASCII))))throw new IOException("Invalid D-Bus signature string");number(raw.length,c=='g'?1:4);raw(raw);number(0,1);}
            else if(c=='v'){Variant variant=(Variant)v;var ts=types(variant.signature);if(ts.size()!=1)throw new IOException("Invalid D-Bus variant");value(scalar('g'),variant.signature,depth+1);value(ts.getFirst(),variant.value,depth+1);}
            else if(c=='a'){int length=at;number(0,4);Type child=t.children.getFirst();align(child.alignment());int start=at;
                if(child.code=='y')raw((byte[])v);
                else if(v instanceof Map<?,?> map){for(var e:map.entrySet())value(child,List.of(e.getKey(),e.getValue()),depth+1);}
                else for(Object item:(Iterable<?>)v)value(child,item,depth+1);
                int size=at-start;for(int i=0;i<4;i++)bytes[length+i]=(byte)(size>>>(8*i));
            }else {List<?> values=(List<?>)v;if(values.size()!=t.children.size())throw new IOException("D-Bus struct arity");for(int i=0;i<values.size();i++)value(t.children.get(i),values.get(i),depth+1);}
        }
        byte[] result(){return Arrays.copyOf(bytes,at);}
    }
    private static final class Reader {
        final ByteBuffer bytes;final int base;
        Reader(byte[] data,ByteOrder order,int base){bytes=ByteBuffer.wrap(data).order(order);this.base=base;}
        void align(int n)throws IOException{take((-(base+bytes.position()))&(n-1));}
        byte[] take(int n)throws IOException{if(n<0||n>bytes.remaining())throw new IOException("Truncated D-Bus value");byte[] out=new byte[n];bytes.get(out);return out;}
        Object value(Type t,int depth)throws IOException {
            if(depth>64)throw new IOException("D-Bus value depth limit");align(t.alignment());char c=t.code;
            try {
                if(c=='y')return (long)Byte.toUnsignedInt(bytes.get());if(c=='n')return (long)bytes.getShort();if(c=='q')return (long)Short.toUnsignedInt(bytes.getShort());
                if(c=='i')return (long)bytes.getInt();if(c=='u'||c=='h')return Integer.toUnsignedLong(bytes.getInt());if(c=='x'||c=='t')return bytes.getLong();if(c=='d')return bytes.getDouble();
                if(c=='b'){int b=bytes.getInt();if(b!=0&&b!=1)throw new IOException("Invalid D-Bus boolean");return b==1;}
                if("sog".indexOf(c)>=0){long n=(Long)value(scalar(c=='g'?'y':'u'),depth+1);if(n>MAX_BODY)throw new IOException("D-Bus string limit");byte[] data=take((int)n);if(bytes.get()!=0)throw new IOException("D-Bus string terminator");
                    for(byte b:data)if(b==0||(c=='g'&&b<0))throw new IOException("Invalid D-Bus string");return new String(data,c=='g'?StandardCharsets.US_ASCII:StandardCharsets.UTF_8);}
                if(c=='v'){String sig=(String)value(scalar('g'),depth+1);var ts=types(sig);if(ts.size()!=1)throw new IOException("Invalid D-Bus variant");return new Variant(sig,value(ts.getFirst(),depth+1));}
                if(c=='a'){long n=(Long)value(scalar('u'),depth+1);Type child=t.children.getFirst();align(child.alignment());if(n>bytes.remaining())throw new IOException("Truncated D-Bus array");int end=bytes.position()+(int)n;
                    if(child.code=='y')return take((int)n);var list=new ArrayList<Object>();var map=new LinkedHashMap<Object,Object>();
                    while(bytes.position()<end){Object next=value(child,depth+1);if(child.code=='{'){List<?> pair=(List<?>)next;if(map.put(pair.get(0),pair.get(1))!=null)throw new IOException("Duplicate D-Bus dictionary key");}else list.add(next);}
                    if(bytes.position()!=end)throw new IOException("D-Bus array length mismatch");return child.code=='{'?map:list;}
                var list=new ArrayList<Object>();for(Type child:t.children)list.add(value(child,depth+1));return list;
            }catch(BufferUnderflowException|IndexOutOfBoundsException e){throw new IOException("Truncated D-Bus value",e);}
        }
    }
    static byte[] values(String signature,List<?> values,int base)throws IOException {var ts=types(signature);if(ts.size()!=values.size())throw new IOException("D-Bus argument count");var w=new Writer(base);for(int i=0;i<ts.size();i++)w.value(ts.get(i),values.get(i),0);return w.result();}
    static byte[] encode(int kind,long serial,Map<Integer,Variant> fields,String signature,List<?> args)throws IOException {
        byte[] body=values(signature,args,0);if(body.length>MAX_BODY)throw new IOException("D-Bus body limit");var headers=new LinkedHashMap<>(fields);if(!signature.isEmpty())headers.put(8,new Variant("g",signature));
        var w=new Writer(16);Type field=types("(yv)").getFirst();for(var e:headers.entrySet())w.value(field,List.of(e.getKey(),e.getValue()),0);byte[] header=w.result();if(header.length>MAX_HEADER)throw new IOException("D-Bus header limit");
        int pad=(-header.length)&7;return ByteBuffer.allocate(16+header.length+pad+body.length).order(ByteOrder.LITTLE_ENDIAN).put((byte)'l').put((byte)kind).put((byte)0).put((byte)1).putInt(body.length).putInt((int)serial).putInt(header.length).put(header).put(new byte[pad]).put(body).array();
    }
    static int remaining(byte[] fixed)throws IOException {
        if(fixed.length!=16||(fixed[0]!='l'&&fixed[0]!='B')||fixed[3]!=1||fixed[1]<1||fixed[1]>4)throw new IOException("Invalid D-Bus header");var b=ByteBuffer.wrap(fixed).order(fixed[0]=='l'?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);
        int body=b.getInt(4),serial=b.getInt(8),header=b.getInt(12);if(body<0||body>MAX_BODY||header<0||header>MAX_HEADER||serial==0)throw new IOException("D-Bus message limit");return header+((-header)&7)+body;
    }
    static Message decode(byte[] fixed,byte[] rest)throws IOException {
        if(rest.length!=remaining(fixed))throw new IOException("D-Bus message length mismatch");ByteOrder order=fixed[0]=='l'?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN;
        var f=ByteBuffer.wrap(fixed).order(order);int header=f.getInt(12);var r=new Reader(Arrays.copyOf(rest,header),order,16);var headers=new LinkedHashMap<Integer,Object>();Type field=types("(yv)").getFirst();
        while(r.bytes.hasRemaining()){var pair=(List<?>)r.value(field,0);int id=((Number)pair.get(0)).intValue();if(headers.put(id,((Variant)pair.get(1)).value)!=null)throw new IOException("Duplicate D-Bus header");}
        r=new Reader(Arrays.copyOfRange(rest,header+((-header)&7),rest.length),order,0);Object sig=headers.getOrDefault(8,"");if(!(sig instanceof String signature))throw new IOException("Invalid body signature");var body=new ArrayList<Object>();for(Type t:types(signature))body.add(r.value(t,0));if(r.bytes.hasRemaining())throw new IOException("D-Bus body length mismatch");
        return new Message(fixed[1],fixed[2],Integer.toUnsignedLong(f.getInt(8)),headers,body);
    }
}
