package acr.browser.lightning.object;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import acr.browser.lightning.R;
import acr.browser.lightning.database.BookmarkManager;
import acr.browser.lightning.database.HistoryDatabase;
import acr.browser.lightning.database.HistoryItem;
import acr.browser.lightning.preference.PreferenceManager;
import acr.browser.lightning.utils.ThemeUtils;
import acr.browser.lightning.utils.Utils;

public class SearchAdapter extends BaseAdapter implements Filterable {

    private final List<HistoryItem> mHistory = new ArrayList<>();
    private final List<HistoryItem> mBookmarks = new ArrayList<>();
    private final List<HistoryItem> mSuggestions = new ArrayList<>();
    private final List<HistoryItem> mFilteredList = new ArrayList<>();
    private final List<HistoryItem> mAllBookmarks = new ArrayList<>();
    private HistoryDatabase mDatabaseHandler;
    private final Context mContext;
    private boolean mUseGoogle = true;
    private boolean mIsExecuting = false;
    private final boolean mDarkTheme;
    private final boolean mIncognito;
    private final BookmarkManager mBookmarkManager;
    private static final String ENCODING = "ISO-8859-1";
    private static final long INTERVAL_DAY = 86400000;
    private final String mSearchSubtitle;
    private SearchFilter mFilter;
    private final Drawable mSearchDrawable;
    private final Drawable mHistoryDrawable;
    private final Drawable mBookmarkDrawable;

    public SearchAdapter(Context context, boolean dark, boolean incognito) {
        mDatabaseHandler = HistoryDatabase.getInstance(context.getApplicationContext());
        mBookmarkManager = BookmarkManager.getInstance(context.getApplicationContext());
        mAllBookmarks.addAll(mBookmarkManager.getAllBookmarks(true));
        mUseGoogle = PreferenceManager.getInstance().getGoogleSearchSuggestionsEnabled();
        mContext = context;
        mSearchSubtitle = mContext.getString(R.string.suggestion);
        mDarkTheme = dark || incognito;
        mIncognito = incognito;
        Thread delete = new Thread(new Runnable() {

            @Override
            public void run() {
                deleteOldCacheFiles(mContext);
            }

        });
        mSearchDrawable = ThemeUtils.getThemedDrawable(context, R.drawable.ic_search, mDarkTheme);
        mBookmarkDrawable = ThemeUtils.getThemedDrawable(context, R.drawable.ic_bookmark, mDarkTheme);
        mHistoryDrawable = ThemeUtils.getThemedDrawable(context, R.drawable.ic_history, mDarkTheme);
        delete.start();
    }

    private void deleteOldCacheFiles(Context context) {
        File dir = new File(context.getCacheDir().toString());
        String[] fileList = dir.list(new NameFilter());
        long earliestTimeAllowed = System.currentTimeMillis() - INTERVAL_DAY;
        for (String fileName : fileList) {
            File file = new File(dir.getPath() + fileName);
            if (earliestTimeAllowed > file.lastModified()) {
                file.delete();
            }
        }
    }

    private class NameFilter implements FilenameFilter {

        private static final String ext = ".sgg";

        @Override
        public boolean accept(File dir, String filename) {
            return filename.endsWith(ext);
        }

    }

    public void refreshPreferences() {
        mUseGoogle = PreferenceManager.getInstance().getGoogleSearchSuggestionsEnabled();
        if (!mUseGoogle) {
            mSuggestions.clear();
        }
        mDatabaseHandler = HistoryDatabase.getInstance(mContext.getApplicationContext());
    }

    public void refreshBookmarks() {
        mAllBookmarks.clear();
        mAllBookmarks.addAll(mBookmarkManager.getAllBookmarks(true));
    }

    @Override
    public int getCount() {
        return mFilteredList.size();
    }

    @Override
    public Object getItem(int position) {
        return mFilteredList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return 0;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        SuggestionHolder holder;

        if (convertView == null) {
            LayoutInflater inflater = ((Activity) mContext).getLayoutInflater();
            convertView = inflater.inflate(R.layout.two_line_autocomplete, parent, false);

            holder = new SuggestionHolder();
            holder.mTitle = (TextView) convertView.findViewById(R.id.title);
            holder.mUrl = (TextView) convertView.findViewById(R.id.url);
            holder.mImage = (ImageView) convertView.findViewById(R.id.suggestionIcon);
            convertView.setTag(holder);
        } else {
            holder = (SuggestionHolder) convertView.getTag();
        }
        HistoryItem web;
        web = mFilteredList.get(position);
        holder.mTitle.setText(web.getTitle());
        holder.mUrl.setText(web.getUrl());

        Drawable image;
        switch (web.getImageId()) {
            case R.drawable.ic_bookmark: {
                if (mDarkTheme)
                    holder.mTitle.setTextColor(Color.WHITE);
                image = mBookmarkDrawable;
                break;
            }
            case R.drawable.ic_search: {
                if (mDarkTheme)
                    holder.mTitle.setTextColor(Color.WHITE);
                image = mSearchDrawable;
                break;
            }
            case R.drawable.ic_history: {
                if (mDarkTheme)
                    holder.mTitle.setTextColor(Color.WHITE);
                image = mHistoryDrawable;
                break;
            }
            default:
                if (mDarkTheme)
                    holder.mTitle.setTextColor(Color.WHITE);
                image = mSearchDrawable;
                break;
        }

        holder.mImage.setImageDrawable(image);

        return convertView;
    }

    @Override
    public Filter getFilter() {
        if (mFilter == null) {
            mFilter = new SearchFilter();
        }
        return mFilter;
    }

    private class SearchFilter extends Filter {

        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            FilterResults results = new FilterResults();
            if (constraint == null) {
                return results;
            }
            String query = constraint.toString().toLowerCase(Locale.getDefault());
            if (mUseGoogle && !mIncognito && !mIsExecuting) {
                new RetrieveSearchSuggestions().execute(query);
            }

            int counter = 0;
            mBookmarks.clear();
            for (int n = 0; n < mAllBookmarks.size(); n++) {
                if (counter >= 5) {
                    break;
                }
                if (mAllBookmarks.get(n).getTitle().toLowerCase(Locale.getDefault())
                        .startsWith(query)) {
                    mBookmarks.add(mAllBookmarks.get(n));
                    counter++;
                } else if (mAllBookmarks.get(n).getUrl().contains(query)) {
                    mBookmarks.add(mAllBookmarks.get(n));
                    counter++;
                }

            }
            if (mDatabaseHandler == null) {
                mDatabaseHandler = HistoryDatabase.getInstance(mContext.getApplicationContext());
            }
            List<HistoryItem> historyList = mDatabaseHandler.findItemsContaining(constraint.toString());
            mHistory.clear();
            mHistory.addAll(historyList);
            results.count = 1;
            return results;
        }

        @Override
        public CharSequence convertResultToString(Object resultValue) {
            return ((HistoryItem) resultValue).getUrl();
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            mFilteredList.clear();
            mFilteredList.addAll(getSuggestions());
            notifyDataSetChanged();
        }

    }

    private class SuggestionHolder {
        ImageView mImage;
        TextView mTitle;
        TextView mUrl;
    }

    private class RetrieveSearchSuggestions extends AsyncTask<String, Void, List<HistoryItem>> {

        private XmlPullParserFactory mFactory;
        private XmlPullParser mXpp;

        @Override
        protected List<HistoryItem> doInBackground(String... arg0) {
            mIsExecuting = true;

            List<HistoryItem> filter = new ArrayList<>();
            String query = arg0[0];
            try {
                query = query.replace(" ", "+");
                URLEncoder.encode(query, ENCODING);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            File cache = downloadSuggestionsForQuery(query);
            if (!cache.exists()) {
                return filter;
            }
            InputStream fileInput = null;
            try {
                fileInput = new BufferedInputStream(new FileInputStream(cache));
                if (mFactory == null) {
                    mFactory = XmlPullParserFactory.newInstance();
                    mFactory.setNamespaceAware(true);
                }
                if (mXpp == null) {
                    mXpp = mFactory.newPullParser();
                }
                mXpp.setInput(fileInput, ENCODING);
                int eventType = mXpp.getEventType();
                int counter = 0;
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && "suggestion".equals(mXpp.getName())) {
                        String suggestion = mXpp.getAttributeValue(null, "data");
                        filter.add(new HistoryItem(mSearchSubtitle + " \"" + suggestion + '"',
                                suggestion, R.drawable.ic_search));
                        counter++;
                        if (counter >= 5) {
                            break;
                        }
                    }
                    eventType = mXpp.next();
                }
            } catch (Exception e) {
                return filter;
            } finally {
                Utils.close(fileInput);
            }
            return filter;
        }

        @Override
        protected void onPostExecute(List<HistoryItem> result) {
            mSuggestions.clear();
            mSuggestions.addAll(result);
            mFilteredList.clear();
            mFilteredList.addAll(getSuggestions());
            notifyDataSetChanged();
            mIsExecuting = false;
        }

    }

    private File downloadSuggestionsForQuery(String query) {
        File cacheFile = new File(mContext.getCacheDir(), query.hashCode() + ".sgg");
        if (System.currentTimeMillis() - INTERVAL_DAY < cacheFile.lastModified()) {
            return cacheFile;
        }
        if (!isNetworkConnected(mContext)) {
            return cacheFile;
        }
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            URL url = new URL("http://google.com/complete/search?q=" + query
                    + "&output=toolbar&hl=en");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            in = connection.getInputStream();

            if (in != null) {
                fos = new FileOutputStream(cacheFile);
                int buffer;
                while ((buffer = in.read()) != -1) {
                    fos.write(buffer);
                }
                fos.flush();
            }
            cacheFile.setLastModified(System.currentTimeMillis());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            Utils.close(in);
            Utils.close(fos);
        }
        return cacheFile;
    }

    private boolean isNetworkConnected(Context context) {
        NetworkInfo networkInfo = getActiveNetworkInfo(context);
        return networkInfo != null && networkInfo.isConnected();
    }

    private NetworkInfo getActiveNetworkInfo(Context context) {
        ConnectivityManager connectivity = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            return null;
        }
        return connectivity.getActiveNetworkInfo();
    }

    //TODO Write simpler algorithm
    private List<HistoryItem> getSuggestions() {
        List<HistoryItem> filteredList = new ArrayList<>();

        int suggestionsSize = mSuggestions.size();
        int historySize = mHistory.size();
        int bookmarkSize = mBookmarks.size();

        int maxSuggestions = (bookmarkSize + historySize < 3) ? (5 - bookmarkSize - historySize)
                : (bookmarkSize < 2) ? (4 - bookmarkSize) : (historySize < 1) ? 3 : 2;
        int maxHistory = (suggestionsSize + bookmarkSize < 4) ? (5 - suggestionsSize - bookmarkSize)
                : 1;
        int maxBookmarks = (suggestionsSize + historySize < 3) ? (5 - suggestionsSize - historySize)
                : 2;

        for (int n = 0; n < bookmarkSize && n < maxBookmarks; n++) {
            filteredList.add(mBookmarks.get(n));
        }

        for (int n = 0; n < historySize && n < maxHistory; n++) {
            filteredList.add(mHistory.get(n));
        }

        for (int n = 0; n < suggestionsSize && n < maxSuggestions; n++) {
            filteredList.add(mSuggestions.get(n));
        }
        return filteredList;
    }

}
