package io.github.offlineglass.hook.adapters.taobao;

/** Exact existing sample grid/bounds over one bulk-read native slot. No scaling. */
public final class TaobaoArtworkPixels {
    public record Result(boolean visible, float centerY) {}
    public static Result inspect(int[] pixels, int width, int height, boolean home) {
        if(width<=0||height<=0||(long)width*height>pixels.length)throw new IllegalArgumentException("slot pixels");
        int count=0,stepX=Math.max(1,width/24),stepY=Math.max(1,height/20);
        for(int y=0;y<height;y+=stepY)for(int x=0;x<width;x+=stepX)
            if((pixels[y*width+x]>>>24)>24)count++;
        if(count<4)return new Result(false,Float.NaN);
        int min=height,max=-1;
        if(home)for(int y=0;y<height;y+=2)for(int x=0;x<width;x+=2)
            if((pixels[y*width+x]>>>24)>24){min=Math.min(min,y);max=Math.max(max,y);}
        return new Result(true,home&&max>min?(min+max)/2f:Float.NaN);
    }
}
