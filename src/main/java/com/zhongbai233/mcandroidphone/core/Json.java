package com.zhongbai233.mcandroidphone.core;

import java.io.IOException;
import java.util.*;

/** Small bounded JSON codec for QMP and local process records; no game/library dependency. */
final class Json {
    static final int LIMIT=1024*1024;
    static Object parse(String text) throws IOException {
        if(text.length()>LIMIT)throw new IOException("JSON exceeds limit");
        var p=new Parser(text);Object value=p.value(0);p.space();
        if(p.at!=text.length())throw new IOException("Trailing JSON data");return value;
    }
    @SuppressWarnings("unchecked") static Map<String,Object> object(String text) throws IOException {
        Object value=parse(text);if(!(value instanceof Map<?,?>))throw new IOException("Expected JSON object");
        return (Map<String,Object>)value;
    }
    static String write(Object value) {
        var out=new StringBuilder();write(value,out,0);
        if(out.length()>LIMIT)throw new IllegalArgumentException("JSON exceeds limit");return out.toString();
    }
    private static void write(Object v,StringBuilder b,int depth) {
        if(depth>64)throw new IllegalArgumentException("JSON nesting limit");
        if(v==null)b.append("null");
        else if(v instanceof String s) {
            b.append('"');for(int i=0;i<s.length();i++) {
                char c=s.charAt(i);switch(c) {
                    case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");
                    case '\r'->b.append("\\r");case '\t'->b.append("\\t");
                    default->{if(c<32||Character.isSurrogate(c))b.append(String.format("\\u%04x",(int)c));else b.append(c);}
                }
            }b.append('"');
        }else if(v instanceof Boolean)b.append(v);
        else if(v instanceof Number n) {if(!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Nonfinite JSON number");b.append(n);}
        else if(v instanceof Map<?,?> m) {
            b.append('{');boolean first=true;for(var e:m.entrySet()) {
                if(!(e.getKey() instanceof String))throw new IllegalArgumentException("JSON key must be string");
                if(!first)b.append(',');first=false;write(e.getKey(),b,depth+1);b.append(':');write(e.getValue(),b,depth+1);
            }b.append('}');
        }else if(v instanceof Iterable<?> list) {
            b.append('[');boolean first=true;for(Object e:list){if(!first)b.append(',');first=false;write(e,b,depth+1);}b.append(']');
        }else throw new IllegalArgumentException("Unsupported JSON value");
    }
    private static final class Parser {
        final String s;int at;Parser(String s){this.s=s;}
        void space(){while(at<s.length()&&" \r\n\t".indexOf(s.charAt(at))>=0)at++;}
        boolean take(char c){space();if(at<s.length()&&s.charAt(at)==c){at++;return true;}return false;}
        IOException bad(){return new IOException("Invalid JSON at "+at);}
        Object value(int depth)throws IOException {
            if(depth>64)throw bad();space();if(at>=s.length())throw bad();char c=s.charAt(at);
            if(c=='"')return string();
            if(take('{')){var m=new LinkedHashMap<String,Object>();if(take('}'))return m;
                do{space();String key=string();if(!take(':')||m.containsKey(key))throw bad();m.put(key,value(depth+1));}while(take(','));
                if(!take('}'))throw bad();return m;}
            if(take('[')){var l=new ArrayList<Object>();if(take(']'))return l;
                do{l.add(value(depth+1));}while(take(','));if(!take(']'))throw bad();return l;}
            for(String word:List.of("true","false","null"))if(s.startsWith(word,at)){at+=word.length();return word.equals("null")?null:word.equals("true");}
            int start=at;while(at<s.length()&&"-+0123456789.eE".indexOf(s.charAt(at))>=0)at++;
            String number=s.substring(start,at);
            if(!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw bad();
            try{if(number.indexOf('.')<0&&number.indexOf('e')<0&&number.indexOf('E')<0)return Long.parseLong(number);
                double n=Double.parseDouble(number);if(!Double.isFinite(n))throw bad();return n;
            }catch(NumberFormatException e){throw bad();}
        }
        String string()throws IOException {
            if(at>=s.length()||s.charAt(at++)!='"')throw bad();var b=new StringBuilder();
            while(at<s.length()) {
                char c=s.charAt(at++);if(c=='"')return b.toString();if(c<32)throw bad();
                if(c!='\\'){b.append(c);continue;}if(at==s.length())throw bad();
                c=s.charAt(at++);switch(c) {
                    case '"','\\','/'->b.append(c);case 'b'->b.append('\b');case 'f'->b.append('\f');
                    case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');
                    case 'u'->{if(at+4>s.length())throw bad();try{b.append((char)Integer.parseInt(s.substring(at,at+4),16));}catch(NumberFormatException e){throw bad();}at+=4;}
                    default->throw bad();
                }
            }throw bad();
        }
    }
}
