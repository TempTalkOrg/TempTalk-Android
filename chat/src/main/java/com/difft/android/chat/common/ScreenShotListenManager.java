package com.difft.android.chat.common;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.core.SingleObserver;
import io.reactivex.rxjava3.core.SingleOnSubscribe;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class ScreenShotListenManager {

    private static final String TAG = "ScreenShotListenManager";

    /**
     * 读取媒体数据库时需要读取的列, 其中 WIDTH 和 HEIGHT 字段在 API 16 以后才有
     */
    private final String[] MEDIA_PROJECTIONS = {
            MediaStore.Images.ImageColumns.DATA,
            MediaStore.Images.ImageColumns.DATE_TAKEN,
            MediaStore.Images.ImageColumns.WIDTH,
            MediaStore.Images.ImageColumns.HEIGHT,
    };

    /**
     * 截屏依据中的路径判断关键字
     */
    private final String[] KEYWORDS = {
            "screenshot", "screen_shot", "screen-shot", "screen shot",
            "screencapture", "screen_capture", "screen-capture", "screen capture",
            "screencap", "screen_cap", "screen-cap", "screen cap"
    };

    private Point sScreenRealSize;

    /**
     * 已回调过的路径
     */
    private final List<String> sHasCallbackPaths = new ArrayList<String>();

    private Context mContext;

    private OnScreenShotListener mListener;

    private long mStartListenTime;

    /**
     * 内部存储器内容观察者
     */
    private MediaContentObserver mInternalObserver;

    /**
     * 外部存储器内容观察者
     */
    private MediaContentObserver mExternalObserver;


    public ScreenShotListenManager(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("The context must not be null.");
        }
        mContext = context;

        // 获取屏幕真实的分辨率
        if (sScreenRealSize == null) {
            sScreenRealSize = getRealScreenSize();
        }
    }

    /**
     * 启动监听
     */
    public void startListen() {
        sHasCallbackPaths.clear();

        // 记录开始监听的时间戳
        mStartListenTime = System.currentTimeMillis();
        // 创建内容观察者
        mInternalObserver = new MediaContentObserver(MediaStore.Images.Media.INTERNAL_CONTENT_URI, null);
        mExternalObserver = new MediaContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null);

        // 注册内容观察者
        mContext.getContentResolver().registerContentObserver(
                MediaStore.Images.Media.INTERNAL_CONTENT_URI,
                true,
                mInternalObserver
        );
        mContext.getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                mExternalObserver
        );
    }

    /**
     * 停止监听
     */
    public void stopListen() {
        // 注销内容观察者
        if (mInternalObserver != null && mContext != null) {
            try {
                mContext.getContentResolver().unregisterContentObserver(mInternalObserver);
            } catch (Exception e) {
                e.printStackTrace();
            }
            mInternalObserver = null;
        }
        if (mExternalObserver != null && mContext != null) {
            try {
                mContext.getContentResolver().unregisterContentObserver(mExternalObserver);
            } catch (Exception e) {
                e.printStackTrace();
            }
            mExternalObserver = null;
        }

        // 清空数据
        mStartListenTime = 0;
        sHasCallbackPaths.clear();
    }

    /**
     * 处理媒体数据库的内容改变
     */
    private ScreenData handleMediaContentChange(Uri contentUri) {
        Cursor cursor = null;
        try {
            // 数据改变时查询数据库中最后加入的一条数据
            if (Build.VERSION.SDK_INT >= 26) {
                // Android R 条件限制需要使用 bundle 否则报：java.lang.IllegalArgumentException: Invalid token limit
                Bundle bundle = new Bundle();
                String[] array = {MediaStore.Images.ImageColumns.DATE_ADDED};
                // 按照该列  倒叙 取1条
                bundle.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, array);
                bundle.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
                bundle.putInt(ContentResolver.QUERY_ARG_LIMIT, 1);

                cursor = mContext.getContentResolver().query(
                        contentUri,
                        MEDIA_PROJECTIONS,
                        bundle, null);
            } else {
                cursor = mContext.getContentResolver().query(
                        contentUri,
                        MEDIA_PROJECTIONS,
                        null,
                        null,
                        MediaStore.Images.ImageColumns.DATE_ADDED + " desc limit 1"
                );
            }


            if (cursor == null) {
                return null;
            }
            if (!cursor.moveToFirst()) {
                return null;
            }

            // 获取各列的索引
            int dataIndex = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
            int dateTakenIndex = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN);
            int widthIndex = -1;
            int heightIndex = -1;
            widthIndex = cursor.getColumnIndex(MediaStore.Images.ImageColumns.WIDTH);
            heightIndex = cursor.getColumnIndex(MediaStore.Images.ImageColumns.HEIGHT);


            // 获取行数据
            String data = cursor.getString(dataIndex);
            long dateTaken = cursor.getLong(dateTakenIndex);
            int width = cursor.getInt(widthIndex);
            int height = cursor.getInt(heightIndex);
            return new ScreenData(data, dateTaken, width, height);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
    }

    /**
     * 处理获取到的一行数据
     */
    private void handleMediaRowData(ScreenData screenData) {
        if (checkScreenShot(screenData)) {
            if (mListener != null && !checkCallback(screenData.getData())) {
                mListener.onShot(screenData.getData());
            }
        }
    }

    /**
     * 判断指定的数据行是否符合截屏条件
     */
    private boolean checkScreenShot(ScreenData screenData) {
        /*
         * 判断依据一: 时间判断
         */
        // 如果加入数据库的时间在开始监听之前, 或者与当前时间相差大于10秒, 则认为当前没有截屏
        if (screenData.getDateTaken() < mStartListenTime || (System.currentTimeMillis() - screenData.getDateTaken()) > 10 * 1000) {
            return false;
        }

        /*
         * 判断依据二: 尺寸判断
         */
        if (sScreenRealSize != null) {
            // 如果图片尺寸超出屏幕, 则认为当前没有截屏
            if (!((screenData.getWidth() <= sScreenRealSize.x && screenData.getHeight() <= sScreenRealSize.y) ||
                    (screenData.getHeight() <= sScreenRealSize.x && screenData.getWidth() <= sScreenRealSize.y))) {
                return false;
            }
        }

        /*
         * 判断依据三: 路径判断
         */
        String data = screenData.getData();
        if (TextUtils.isEmpty(data)) {
            return false;
        }
        data = data.toLowerCase();
        // 判断图片路径是否含有指定的关键字之一, 如果有, 则认为当前截屏了
        for (String keyWork : KEYWORDS) {
            if (data.contains(keyWork)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断是否已回调过, 某些手机ROM截屏一次会发出多次内容改变的通知; <br/>
     * 删除一个图片也会发通知, 同时防止删除图片时误将上一张符合截屏规则的图片当做是当前截屏.
     */
    private boolean checkCallback(String imagePath) {
        if (sHasCallbackPaths.contains(imagePath)) {
            return true;
        }
        // 大概缓存15~20条记录便可
        if (sHasCallbackPaths.size() >= 20) {
            for (int i = 0; i < 5; i++) {
                sHasCallbackPaths.remove(0);
            }
        }
        sHasCallbackPaths.add(imagePath);
        return false;
    }

    /**
     * 获取屏幕分辨率
     */
    private Point getRealScreenSize() {
        Point screenSize = null;
        try {
            screenSize = new Point();
            WindowManager windowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            Display defaultDisplay = windowManager.getDefaultDisplay();
            defaultDisplay.getRealSize(screenSize);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return screenSize;
    }

    /**
     * 设置截屏监听器
     */
    public void setListener(OnScreenShotListener listener) {
        mListener = listener;
    }

    public interface OnScreenShotListener {
        void onShot(String imagePath);
    }

    /**
     * 媒体内容观察者(观察媒体数据库的改变)
     */
    private class MediaContentObserver extends ContentObserver {

        private Uri mContentUri;

        public MediaContentObserver(Uri contentUri, Handler handler) {
            super(handler);
            mContentUri = contentUri;
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            // TODO onChange 执行多次解决办法
            // TODO 解决1：后台情况不予上报
//            if (PublicPreferencesUtils.isBackGround()) {
//                return;
//            }
            // TODO 解决2：线程池复用
            // TODO 解决3：相同的uri  过滤
            // TODO 解决3：分时段监听  【但是就不是实时监听了】
            handleScreenData(mContentUri);
        }
    }

    /**
     * TODO 待解决：有些场景 多次 回调 多次创建线程
     * 改用本地的线程池
     * @param mContentUri
     */
    private void handleScreenData(Uri mContentUri) {
        Log.d(TAG,"--------------handleScreenData:"+mContentUri);
        Single.create((SingleOnSubscribe<ScreenData>) e -> {
                    ScreenData screenData = handleMediaContentChange(mContentUri);
                    if (screenData != null) {
                        e.onSuccess(screenData);
                    } else {
                        e.onError(new Exception("获取截图信息失败"));
                    }
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SingleObserver<ScreenData>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                    }

                    @Override
                    public void onSuccess(@NonNull ScreenData screenData) {
                        // 处理获取到的第一行数据
                        handleMediaRowData(screenData);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                    }
                });
    }

    private class ScreenData {
        private final String data;
        private final long dateTaken;
        private final int width;
        private final int height;

        public ScreenData(String data, long dateTaken, int width, int height) {
            this.data = data;
            this.dateTaken = dateTaken;
            this.width = width;
            this.height = height;
        }

        public String getData() {
            return data;
        }

        public long getDateTaken() {
            return dateTaken;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
    }
}

