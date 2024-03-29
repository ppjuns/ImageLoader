package com.ppjun.magic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Package :com.ppjun.magic
 * @Description :
 * @Author :Rc3
 * @Created at :2016/8/12 11:20.
 */
public class ImageLoader {
    public static final String TAG = ImageReSizer.class.getSimpleName();
    public static final int MESSAGE_POST_RESULT = 1;
    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIUMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE = 10L;
    private static final int TAG_KEY_URI = R.id.imageloader_uri;
    private static final long DISK_CACHE_SIZE = 1024 * 1024 * 50;//50M
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private static final int DISK_CACHE_INDEX = 0;
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "ImageLoader#" + mCount.getAndIncrement());
        }
    };
    public static final Executor THREAD_POOL_EXECUTOR =
            new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIUMUM_POOL_SIZE, KEEP_ALIVE, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), sThreadFactory);
    private boolean mIsDiskLruCacheCreated = false;
    private Handler mMainHeader = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageview = result.imageview;
            imageview.setImageBitmap(result.bitmap);
            String uri = (String) imageview.getTag(TAG_KEY_URI);
            if (uri.equals(result.uri)) {
                imageview.setImageBitmap(result.bitmap);
            } else {
                Log.e(TAG, "set image bitmap ,but the url is changed");

            }
        }
    };
    private ImageReSizer mImageReSizer = new ImageReSizer();
    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;
    private Context mContext;


    private ImageLoader(Context context) {
        mContext = context.getApplicationContext();
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getRowBytes() * bitmap.getHeight() / 1024;
            }
        };

        File diskCacheDir = getDiskCacheDir(mContext, "bitmap");
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }
        if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
            } catch (IOException e) {
                e.printStackTrace();
            }
            mIsDiskLruCacheCreated = true;


        }


    }

    private long getUsableSpace(File path) {

        if(Build.VERSION.SDK_INT>= Build.VERSION_CODES.GINGERBREAD){
            return path.getUsableSpace();
        }
        final StatFs stats=new StatFs(path.getPath());
        return (long)stats.getBlockSize()*(long)stats.getAvailableBlocks();
    }

    public static ImageLoader build(Context context) {
        return new ImageLoader(context);
    }


    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemCache(key) == null) {

            mMemoryCache.put(key, bitmap);
        }
    }

    private Bitmap getBitmapFromMemCache(String key) {
        return mMemoryCache.get(key);
    }

    public void bindBitmap(final String uri, final ImageView imageview) {

        bindBitmap(uri, imageview, 0, 0);
    }

    public void bindBitmap(final String uri, final ImageView imageview, final int reqWidth, final int reqHeight) {

        imageview.setTag(TAG_KEY_URI, uri);
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {
            imageview.setImageBitmap(bitmap);
            return;
        }
        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = loadBitmap(uri, reqWidth, reqHeight);
                if (bitmap != null) {
                    LoaderResult result = new LoaderResult(imageview, uri, bitmap);
                    mMainHeader.obtainMessage(MESSAGE_POST_RESULT, result).sendToTarget();

                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }

    public Bitmap loadBitmap(String uri, int reqWidth, int reqHeight) {
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {
            Log.d(TAG, "loadbitmapFromMemCache,Url" + uri);
            return bitmap;
        }

        try {
            bitmap = loadBitmapFromDiskCache(uri, reqWidth, reqHeight);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (bitmap != null) {
            Log.d(TAG, "loadBitmapFromDiskCache,Url" + uri);
            return bitmap;
        }
        try {
            bitmap = loadBitmapFromHttp(uri, reqWidth, reqHeight);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (bitmap != null) {
            Log.d(TAG, "loadBitmapFromHttp,Url" + uri);
            return bitmap;
        }
        if (bitmap == null && !mIsDiskLruCacheCreated) {
            Log.d(TAG, "diskCache is not created,Url");
            bitmap = downloadBitmapFromUrl(uri);
        }
        return bitmap;

    }

    private Bitmap loadBitmapFromHttp(String uri, int reqWidth, int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new RuntimeException("can not visit network from UI thread3");
        }
        if (mDiskLruCache == null) {
            return null;
        }
        String key = hashKeyFromUrl(uri);
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if (editor != null) {
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            if (downloadUrlToStream(uri, outputStream)) {
                editor.commit();
            } else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }
        return loadBitmapFromDiskCache(uri, reqWidth, reqHeight);

    }

    private Bitmap loadBitmapFromDiskCache(String uri, int reqWidth, int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "load bitmap from UI thread,it's not recommended");
        }
        if (mDiskLruCache == null) {
            return null;
        }
        Bitmap bitmap = null;
        String key = hashKeyFromUrl(uri);
        DiskLruCache.Snapshot snapShot = mDiskLruCache.get(key);
        if (snapShot != null) {
            FileInputStream fileInputStream = (FileInputStream) snapShot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = mImageReSizer.decodeSampleBitmapFromFileDescriptor(fileDescriptor, reqWidth, reqHeight);
            if (bitmap != null) {
                addBitmapToMemoryCache(key, bitmap);
            }

        }
        return bitmap;

    }

    public boolean downloadUrlToStream(String urlStr, OutputStream outputStream) {

        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(urlStr);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            out=new BufferedOutputStream(outputStream,IO_BUFFER_SIZE);
            int line;
            while ((line = in.read()) != -1) {
                out.write(line);
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            try {
                out.close();
                in.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
        return false;
    }

    private Bitmap downloadBitmapFromUrl(String urlStr) {
        HttpURLConnection urlConnection = null;
        Bitmap bitmap = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(urlStr);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);

            return bitmap;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            try {

                in.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
        return bitmap;

    }

    private Bitmap loadBitmapFromMemCache(String uri) {
        final String key = hashKeyFromUrl(uri);
        Bitmap bitmap = getBitmapFromMemCache(key);
        return bitmap;
    }

    private String hashKeyFromUrl(String uri) {
        String cacheKey = null;
        final MessageDigest mDigest;
        try {
            mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(uri.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        return cacheKey;
    }

    private String bytesToHexString(byte[] digest) {
          StringBuilder sb=new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            String hex=Integer.toHexString(0xFF & digest[i]);
            if(hex.length()==1){
                sb.append('0');

            }
            sb.append(hex);
        }
        return sb.toString();
    }

    private File getDiskCacheDir(Context context, String uniqueName) {

        boolean externalStorageAvailable= Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        final  String cachePath;
        if(externalStorageAvailable){
            cachePath=context.getExternalCacheDir().getPath();
        }else{
            cachePath=context.getCacheDir().getPath();
        }
        return  new File(cachePath+File.separator+uniqueName);
    }

    private static class LoaderResult {
        public ImageView imageview;
        public String uri;
        public Bitmap bitmap;

        public LoaderResult(ImageView imageview, String uri, Bitmap bitmap) {
            this.imageview = imageview;
            this.uri = uri;
            this.bitmap = bitmap;
        }
    }

}
