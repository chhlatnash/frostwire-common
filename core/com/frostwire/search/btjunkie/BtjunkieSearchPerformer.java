/*
 * Created by Angel Leon (@gubatron), Alden Torres (aldenml)
 * Copyright (c) 2011-2014, FrostWire(R). All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.frostwire.search.btjunkie;

import com.frostwire.search.*;
import com.frostwire.search.domainalias.DomainAliasManager;
import com.google.code.regexp.Pattern;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BtjunkieSearchPerformer extends CrawlRegexSearchPerformer<BtjunkieSearchResult> {
    private static final int MAX_PAGES = 2;
    private static final int MAX_SEARCH_RESULTS = 20;

    private static Pattern YEAR_MONTH_DATE_PATTERN = Pattern.compile("([\\d]{4})-([\\d]{2})-([\\d]{2})");
    private static final String HTML_REGEX = "(?is)<tr>.*?<td data-href=\"(?<detailsUrl>.*?)\" class=\"type_td\"><a title=\".*?<a title=\"View details for [\\d]+ - (?<title>.*?)\" href=\".*?\"><h2>.*?<td data-href=\"(?<magnet>.*?)\" class=\"magnet_td\">.*?<td class=\"size_td\">(?<size>.*?)</td>.*?<td class=\"date_td\">(?<date>.*?)</td>.*?<td class=\"seed_td\">(?<seeds>.*?)</td>.*?</tr>";
    private static final Pattern PATTERN = Pattern.compile(HTML_REGEX);

    private final static long[] BYTE_MULTIPLIERS = new long[]{1, 2 << 9, 2 << 19, 2 << 29, 2 << 39, 2 << 49};

    private static final Map<String, Integer> UNIT_TO_BYTE_MULTIPLIERS_MAP;

    private static final Pattern sizePattern;

    static {
        UNIT_TO_BYTE_MULTIPLIERS_MAP = new HashMap<String, Integer>();
        UNIT_TO_BYTE_MULTIPLIERS_MAP.put("B", 0);
        UNIT_TO_BYTE_MULTIPLIERS_MAP.put("KB", 1);
        UNIT_TO_BYTE_MULTIPLIERS_MAP.put("MB", 2);
        UNIT_TO_BYTE_MULTIPLIERS_MAP.put("GB", 3);
        UNIT_TO_BYTE_MULTIPLIERS_MAP.put("TB", 4);
        UNIT_TO_BYTE_MULTIPLIERS_MAP.put("PB", 5);
        sizePattern = Pattern.compile("([\\d+\\.]+) ([BKMGTP]+)");
    }

    public BtjunkieSearchPerformer(DomainAliasManager domainAliasManager, long token, String keywords, int timeout) {
        super(domainAliasManager, token, keywords, timeout, 1, MAX_PAGES, MAX_SEARCH_RESULTS);
    }

    @Override
    protected String getUrl(int page, String encodedKeywords) {
        return "http://btjunkie.eu/all/by-default_sort/desc/page" + page + "/" + encodedKeywords;
    }

    @Override
    public Pattern getPattern() {
        return PATTERN;
    }

    @Override
    public BtjunkieSearchResult fromMatcher(SearchMatcher matcher) {
        final String domainName = getDomainNameToUse();

        BtjunkieSearchResult sr = new BtjunkieSearchResult(
                domainName,
                "http://" + domainName + matcher.group("detailsUrl"),
                parseFileName(matcher.group("title")),
                parseDisplayName(matcher.group("title")),
                matcher.group("magnet"),
                PerformersHelper.parseInfoHash(matcher.group("magnet")),
                parseSize(matcher.group("size")),
                parseDate(matcher.group("date")),
                parseSeeds(matcher.group("seeds")));

        return sr;
    }

    private long parseDate(String dateString) {
        Calendar instance = Calendar.getInstance();
        long result = instance.getTimeInMillis();
        SearchMatcher matcher = SearchMatcher.from(YEAR_MONTH_DATE_PATTERN.matcher(new MaxIterCharSequence(dateString, dateString.length() * 2)));
        if (matcher.find()) {
            try {
                instance.clear();
                int year = Integer.valueOf(matcher.group(1));
                int month = Integer.valueOf(matcher.group(2));
                int date = Integer.valueOf(matcher.group(3));
                instance.set(year, month, date);
                result = instance.getTimeInMillis();
            } catch (Throwable t) {
                // return now.
            }
        }

        return result;
    }

    private int parseSeeds(String group) {
        try {
            return Integer.parseInt(group);
        } catch (Exception e) {
            return 0;
        }
    }

    private String parseDisplayName(String rawdisplayname) {
        return rawdisplayname.replaceAll("[\\:*?\"|\\[\\]]+"," ");
    }

    private String parseFileName(String filename) {
        return filename.replaceAll("[\\\\/:*?\"<>|\\[\\]]+", "_") + ".torrent";
    }

    private long parseSize(String sizeString) {
        System.out.println("parseSize of ["+ sizeString+"]");
        long result = 0;
        SearchMatcher matcher = SearchMatcher.from(sizePattern.matcher(new MaxIterCharSequence(sizeString, sizeString.length()*2)));
        if (matcher.find()) {
            String amount = matcher.group(1);
            String unit = matcher.group(2);

            long multiplier = BYTE_MULTIPLIERS[UNIT_TO_BYTE_MULTIPLIERS_MAP.get(unit)];

            //fractional size
            if (amount.indexOf(".") > 0) {
                float floatAmount = Float.parseFloat(amount);
                result = (long) (floatAmount * multiplier);
            }
            //integer based size
            else {
                int intAmount = Integer.parseInt(amount);
                result = (long) (intAmount * multiplier);
            }
        }
        return result;
    }

    @Override
    protected String getCrawlUrl(BtjunkieSearchResult sr) {
        return sr.getTorrentUrl();
    }

    @Override
    protected List<? extends SearchResult> crawlResult(BtjunkieSearchResult sr, byte[] data) throws Exception {
        return PerformersHelper.crawlTorrent(this, sr, data);
    }

    @Override
    protected int preliminaryHtmlPrefixOffset(String page) {
        return 18000;
    }

    @Override
    protected int preliminaryHtmlSuffixOffset(String page) {
        return page.length() - 5000;
    }

    /**
    public static void main(String[] args) throws IOException {
        System.out.println(HTML_REGEX);

        String fileStr = IOUtils.toString(new FileInputStream("/Users/gubatron/Desktop/btjunkie.html"),"utf-8");
        com.google.code.regexp.Matcher matcher = PATTERN.matcher(fileStr);

        int found = 0;
        while (matcher.find()) {
            found++;
            System.out.println("\nfound " + found);

            System.out.println("group detailsUrl: " + matcher.group("detailsUrl"));
            System.out.println("group title: " + matcher.group("title"));
            System.out.println("group magnet: " + matcher.group("magnet"));
            System.out.println("group size: " + matcher.group("size"));
            System.out.println("group date: " + matcher.group("date"));
            System.out.println("group seeds: " + matcher.group("seeds"));
        }

        System.out.println("Ended.");
    }
     */
}