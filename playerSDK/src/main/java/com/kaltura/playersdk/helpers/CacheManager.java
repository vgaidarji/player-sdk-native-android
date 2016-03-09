package com.kaltura.playersdk.helpers;

import android.content.Context;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebResourceResponse;

import com.kaltura.playersdk.Utilities;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static com.kaltura.playersdk.helpers.KStringUtilities.md5;


/**
 * Created by nissimpardo on 25/10/15.
 */
public class CacheManager {
    private static final String TAG = "CacheManager";
    public static final String CACHED_STRINGS_JSON = "CachedStrings.json";
    private static CacheManager ourInstance = new CacheManager();
    private JSONObject mCacheConditions;
    private Context mContext;
    private CacheSQLHelper mSQLHelper;
    private String mBaseURL;
    private float mCacheSize = 0;
    private String mCachePath;

    public static CacheManager getInstance() {
        return ourInstance;
    }

    private CacheManager() {
    }

    public void setContext(Context context) {
        mContext = context;
        if (mSQLHelper == null) {
            mSQLHelper = new CacheSQLHelper(context);
        }
    }

    public void setBaseURL(String baseURL) {
        mBaseURL = baseURL;
    }

    public void setCacheSize(float cacheSize) {
        mCacheSize = cacheSize;
    }

    private String getCachePath() {
        if (mCachePath == null) {
            mCachePath = mContext.getFilesDir() + "/kaltura/";
            File cacheDir = new File(mCachePath);
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
        }
        return mCachePath;
    }
    
    private JSONObject getCacheConditions() {
        
        if (mCacheConditions == null) {
            String string = Utilities.readAssetToString(mContext, CACHED_STRINGS_JSON);
            if (string != null) {
                try {
                    mCacheConditions = new JSONObject(string);
                } catch (JSONException e) {
                    Log.e(TAG, "Invalid json", e);
                }
            }
        }

        return mCacheConditions;
    }

    public boolean shouldStore(Uri uri) {
        String uriString = uri.toString();
        JSONObject conditions = getCacheConditions();

        String key = uriString.startsWith(mBaseURL) ? "withDomain" : "substring";

        try {
            JSONObject object = conditions.getJSONObject(key);
            for (Iterator<String> it = object.keys(); it.hasNext(); ) {
                String str = it.next();
                if (uriString.contains(str)) {
                    return true;
                }
            }

        } catch (JSONException e) {
            Log.w(TAG, "Can't find required configuration data in " + CACHED_STRINGS_JSON, e);
        }

        return false;
    }

    private void deleteLessUsedFiles(long newCacheSize) {
        long freeBytesInternal = new File(mContext.getFilesDir().getAbsoluteFile().toString()).getFreeSpace();
//        long freeBytesExternal = new File(getExternalFilesDir(null).toString()).getFreeSpace();
        long cahceSize = (long)(mCacheSize * 1024 * 1024);
        long actualCacheSize = Math.min(cahceSize, freeBytesInternal);
        Log.d("KalturaCacheSize", String.valueOf(mSQLHelper.cacheSize()));
        boolean shouldDeleteLessUsedFiles = mSQLHelper.cacheSize() + newCacheSize > actualCacheSize;
        if (shouldDeleteLessUsedFiles) {
            mSQLHelper.deleteLessUsedFiles(mSQLHelper.cacheSize() + newCacheSize - actualCacheSize, new CacheSQLHelper.KSQLHelperDeleteListener() {
                @Override
                public void fileDeleted(String fileId) {
                    File lessUsedFile = new File(getCachePath() + fileId);
                    if (lessUsedFile.exists()) {
                        lessUsedFile.delete();
                    }
                }
            });
        }
    }

    private void appendHeaders(HttpURLConnection connection, Map<String, String> headers, String method) {
        try {
            connection.setRequestMethod(method);
        } catch (ProtocolException e) {
            Log.e(TAG, "Invalid method " + method, e);
            // This can't really happen. But if it did, and we're on a debug build, the app should crash.
            throw new IllegalArgumentException(e);
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            connection.setRequestProperty(entry.getKey(), entry.getValue());
        }
    }
    
    public boolean removeCachedResponse(Uri requestUrl) {
        String fileId = getCacheFileId(requestUrl);

        if (!mSQLHelper.removeFile(fileId)) {
            Log.e(TAG, "Failed to remove cache entry for request: " + requestUrl);
            return false;
        } else {
            File file = new File(getCachePath() + fileId);

            if (!file.delete()) {
                Log.e(TAG, "Failed to delete file for request: " + requestUrl);
                return false;
            }
        }
        return true;
    }
    
    public void cacheResponse(Uri requestUrl) throws IOException {
        WebResourceResponse resp = getResponse(requestUrl, Collections.<String, String>emptyMap(), "GET");
        InputStream inputStream = resp.getData();


        // Must fully read the input stream so that it gets cached. But we don't need the data now.
        //noinspection StatementWithEmptyBody
        while (inputStream.read() >= 0);    // inputStream is buffered
        
        inputStream.close();
    }
    
    public WebResourceResponse getResponse(Uri requestUrl, Map<String, String> headers, String method) throws IOException {
        if (!shouldStore(requestUrl)) {
            return null;
        }
        InputStream inputStream = null;
        String fileName = getCacheFileId(requestUrl);
        String filePath = getCachePath() + fileName;
        String contentType = null;
        String encoding = null;
        HashMap<String, Object> fileParams = mSQLHelper.fetchParamsForFile(fileName);
        if (mSQLHelper.sizeForId(fileName) > 0 && fileParams != null) {
            FileInputStream fileInputStream = new FileInputStream(filePath);
            inputStream = new BufferedInputStream(fileInputStream);
            contentType = (String)fileParams.get(CacheSQLHelper.MimeType);
            encoding = (String)fileParams.get(CacheSQLHelper.Encoding);
            mSQLHelper.updateDate(fileName);
        } else {
            URL url = null;
            HttpURLConnection connection = null;
            url = new URL(requestUrl.toString());
            connection = (HttpURLConnection) url.openConnection();
            appendHeaders(connection, headers, method);
            connection.connect();
            contentType = connection.getContentType();
            if (contentType == null) {
                contentType = "";
            }
            String[] contentTypeParts = TextUtils.split(contentType, ";");
            if (contentTypeParts.length >= 2) {
                contentType = contentTypeParts[0].trim();
                encoding = contentTypeParts[1].trim();
            }
            mSQLHelper.addFile(fileName, contentType, encoding);
            inputStream = new CachingInputStream(filePath, url.openStream(), new CachingInputStream.KInputStreamListener() {
                @Override
                public void streamClosed(long fileSize, String filePath) {
                    int trimLength = getCachePath().length();
                    String fileId = filePath.substring(trimLength);
                    mSQLHelper.updateFileSize(fileId, fileSize);
                    deleteLessUsedFiles(fileSize);
                }
            });


        }
//        Log.d(TAG, "Stored: " + contentType + " " + encoding + " " + requestUrl.toString());
        return new WebResourceResponse(contentType, encoding, inputStream);
    }

    @NonNull
    private String getCacheFileId(Uri requestUrl) {
        if (requestUrl.getFragment() != null) {
            String localContentId = KStringUtilities.extractFragmentParam(requestUrl, "localContentId");
            if (!TextUtils.isEmpty(localContentId)) {
                return md5("contentId:" + localContentId);
            }
        }
        return md5(requestUrl.toString());
    }
}
