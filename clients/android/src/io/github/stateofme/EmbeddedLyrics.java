package io.github.stateofme;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** Bounded, read-only tag reader. No audio decoding, filesystem paths or network. */
public final class EmbeddedLyrics {
    private static final int LIMIT = 8 * 1024 * 1024;
    private static final int TEXT_LIMIT = 256 * 1024;
    static byte[] read(InputStream in, int n) throws IOException {
        if(n<0 || n>LIMIT) throw new IOException("Tag size limit");
        byte[] b=new byte[n]; int p=0,r;
        while(p<n && (r=in.read(b,p,n-p))>0)p+=r;
        if(p!=n)throw new EOFException();return b;
    }
    static long be(byte[] b,int p,int n) {long v=0;for(int i=0;i<n;i++)v=(v<<8)|(b[p+i]&255);return v;}
    static int le(byte[] b,int p) {return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
    static int sync(byte[] b,int p) {return ((b[p]&127)<<21)|((b[p+1]&127)<<14)|((b[p+2]&127)<<7)|(b[p+3]&127);}
    static String ascii(byte[] b,int p,int n){return new String(b,p,n,StandardCharsets.ISO_8859_1);}
    static void skip(InputStream in,long n)throws IOException{while(n>0){long s=in.skip(n);if(s>0)n-=s;else if(in.read()<0)throw new EOFException();else n--;}}
    static Charset charset(int enc){return enc==1?StandardCharsets.UTF_16:enc==2?StandardCharsets.UTF_16BE:enc==3?StandardCharsets.UTF_8:StandardCharsets.ISO_8859_1;}
    static int end(byte[] b,int start,int enc){int step=(enc==1||enc==2)?2:1;for(int i=start;i+step<=b.length;i+=step)if(b[i]==0&&(step==1||b[i+1]==0))return i;return b.length;}
    static String text(byte[] b,int start,int count,int enc){return new String(b,start,count,charset(enc)).replace("\u0000","").trim();}
    static String bounded(String s){return s.length()<=TEXT_LIMIT?s:null;}

    public static String read(InputStream input)throws IOException {
        BufferedInputStream in=new BufferedInputStream(input);in.mark(16);byte[] h=read(in,10);in.reset();
        if(ascii(h,0,3).equals("ID3"))return id3(in);
        if(ascii(h,0,4).equals("fLaC"))return flac(in);
        if(ascii(h,4,4).equals("ftyp"))return mp4(in);
        return null;
    }
    static String id3(InputStream in)throws IOException {
        byte[] h=read(in,10);int version=h[3]&255;if(version<3||version>4)return null;
        // Reject global unsynchronization/extended headers instead of guessing offsets.
        if((h[5]&0xc0)!=0)return null;
        byte[] tag=read(in,sync(h,6));
        for(int p=0;p+10<=tag.length;){String name=ascii(tag,p,4);int n=version==4?sync(tag,p+4):(int)be(tag,p+4,4);int flags=tag[p+9]&255;p+=10;
            if(n<=0||n>tag.length-p)break;
            if(flags==0 && n<=TEXT_LIMIT && (name.equals("USLT")||name.equals("SYLT"))){
                byte[] b=Arrays.copyOfRange(tag,p,p+n);if(b.length<5){p+=n;continue;}int enc=b[0]&255;if(enc>3){p+=n;continue;}
                int step=(enc==1||enc==2)?2:1;
                if(name.equals("USLT")){int start=end(b,4,enc)+step;if(start<=b.length)return bounded(text(b,start,b.length-start,enc));}
                else if(b.length>=7 && b[4]==2){ // SYLT timestamps in milliseconds only
                    int q=end(b,6,enc)+step;StringBuilder out=new StringBuilder();
                    while(q<b.length){int e=end(b,q,enc),stamp=e+step;if(stamp+4>b.length)break;long ms=be(b,stamp,4);out.append(String.format(Locale.ROOT,"[%02d:%02d.%03d]",ms/60000,(ms/1000)%60,ms%1000)).append(text(b,q,e-q,enc)).append('\n');q=stamp+4;}
                    if(out.length()>0)return bounded(out.toString());
                }
            }p+=n;
        }return null;
    }
    static String flac(InputStream in)throws IOException {
        read(in,4);int total=0;
        for(int blocks=0;blocks<128;blocks++){byte[] h=read(in,4);int type=h[0]&127,n=(int)be(h,1,3);total+=n;if(total>LIMIT)return null;
            if(type==4){byte[] b=read(in,n);if(n<8)return null;int vendor=le(b,0);if(vendor<0||vendor>n-8)return null;int p=4+vendor,count=le(b,p);p+=4;
                for(int i=0;i<count&&p+4<=n&&i<10000;i++){int len=le(b,p);p+=4;if(len<0||len>n-p)return null;String entry=new String(b,p,len,StandardCharsets.UTF_8);p+=len;int eq=entry.indexOf('=');if(eq>0){String key=entry.substring(0,eq).toUpperCase(Locale.ROOT);if(key.equals("LYRICS")||key.equals("UNSYNCEDLYRICS")||key.equals("SYNCEDLYRICS"))return bounded(entry.substring(eq+1));}}
            }else skip(in,n);
            if((h[0]&128)!=0)break;
        }return null;
    }
    static String mp4(InputStream in)throws IOException {
        for(int boxes=0;boxes<256;boxes++){
            byte[] h;try{h=read(in,8);}catch(EOFException e){return null;}long size=be(h,0,4);String name=ascii(h,4,4);int header=8;
            if(size==1){size=be(read(in,8),0,8);header=16;}if(size<header||size>2147483648L)return null;
            if(name.equals("moov"))return mp4Children(read(in,(int)(size-header)),0,0);
            skip(in,size-header);
        }return null;
    }
    static String mp4Children(byte[] b,int depth,int start) {
        if(depth>8)return null;
        for(int p=start;p+8<=b.length;){long size=be(b,p,4);if(size<8||size>b.length-p)return null;String name=ascii(b,p+4,4);int n=(int)size;
            if(name.equals("\u00a9lyr")){
                int q=p+8;while(q+16<=p+n){int len=(int)be(b,q,4);if(len<16||len>p+n-q)break;if(ascii(b,q+4,4).equals("data")&&len-16<=TEXT_LIMIT)return bounded(new String(b,q+16,len-16,StandardCharsets.UTF_8).trim());q+=len;}
            }
            if(name.equals("udta")||name.equals("meta")||name.equals("ilst")){
                byte[] child=Arrays.copyOfRange(b,p+8,p+n);String found=mp4Children(child,depth+1,name.equals("meta")?4:0);if(found!=null)return found;
            }p+=n;
        }return null;
    }
}
