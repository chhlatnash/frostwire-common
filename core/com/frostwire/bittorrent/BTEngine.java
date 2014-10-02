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

package com.frostwire.bittorrent;

import com.frostwire.jlibtorrent.*;
import com.frostwire.jlibtorrent.alerts.Alert;
import com.frostwire.jlibtorrent.alerts.AlertType;
import com.frostwire.jlibtorrent.alerts.SaveResumeDataAlert;
import com.frostwire.jlibtorrent.alerts.TorrentAlert;
import com.frostwire.jlibtorrent.swig.entry;
import com.frostwire.logging.Logger;
import com.frostwire.search.torrent.TorrentCrawledSearchResult;
import com.frostwire.util.OSUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;

/**
 * @author gubatron
 * @author aldenml
 */
public final class BTEngine {

    private static final Logger LOG = Logger.getLogger(BTEngine.class);

    public static BTContext ctx;

    private final Session session;
    private final Downloader downloader;

    private BTEngineListener listener;

    private boolean isFirewalled;

    public BTEngine() {
        this.session = new Session();
        this.downloader = new Downloader(this.session);

        addEngineListener();
    }

    private static class Loader {
        static BTEngine INSTANCE = new BTEngine();
    }

    public static BTEngine getInstance() {
        if (ctx == null) {
            throw new IllegalStateException("Context can't be null");
        }
        return Loader.INSTANCE;
    }

    public Session getSession() {
        return session;
    }

    public BTEngineListener getListener() {
        return listener;
    }

    public void setListener(BTEngineListener listener) {
        this.listener = listener;
    }

    public void download(File torrent, File saveDir) throws IOException {
        download(torrent, saveDir, null);
    }

    public void download(File torrent, File saveDir, boolean[] selection) {
        if (saveDir == null) {
            saveDir = ctx.dataDir;
        }

        downloader.download(torrent, saveDir, selection);

        saveResumeTorrent(torrent);
    }

    public void download(TorrentInfo ti, File saveDir, boolean[] selection) {
        if (saveDir == null) {
            saveDir = ctx.dataDir;
        }

        downloader.download(ti, saveDir, selection);

        File torrent = saveTorrent(ti);
        saveResumeTorrent(torrent);
    }

    public void download(TorrentInfo ti, File saveDir) {
        if (saveDir == null) {
            saveDir = ctx.dataDir;
        }

        downloader.download(ti, saveDir);

        File torrent = saveTorrent(ti);
        saveResumeTorrent(torrent);
    }

    public void download(TorrentInfo ti, File saveDir, int fileIndex) {
        if (saveDir == null) {
            saveDir = ctx.dataDir;
        }

        TorrentHandle th = downloader.find(ti.getInfoHash());

        if (th != null) {
            Priority[] priorities = th.getFilePriorities();
            if (priorities[fileIndex] == Priority.IGNORE) {
                priorities[fileIndex] = Priority.NORMAL;
                downloader.download(ti, saveDir, priorities, null);
            }
        } else {
            Priority[] priorities = Priority.array(Priority.IGNORE, ti.getNumFiles());
            priorities[fileIndex] = Priority.NORMAL;
            downloader.download(ti, saveDir, priorities, null);
        }

        File torrent = saveTorrent(ti);
        saveResumeTorrent(torrent);
    }

    public void download(TorrentCrawledSearchResult sr, File saveDir) {
        download(sr.getTorrentInfo(), saveDir, sr.getFileIndex());
    }

    public byte[] fetchMagnet(String uri, long timeout) {
        return downloader.fetchMagnet(uri, timeout);
    }

    public void restoreDownloads() {
        File[] torrents = ctx.homeDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return FilenameUtils.getExtension(name).equals("torrent");
            }
        });

        for (File t : torrents) {
            try {
                File resumeFile = new File(ctx.homeDir, FilenameUtils.getBaseName(t.getName()) + ".resume");
                session.asyncAddTorrent(t, null, resumeFile);
            } catch (Throwable e) {
                LOG.error("Error restoring torrent download", e);
            }
        }
    }

    public void stop() {
        saveSettings();
// TODO:BITTORRENT
                    /*
                    if (AzureusStarter.isAzureusCoreStarted()) {
						LOG.debug("LifecycleManagerImpl.handleEvent - SHUTINGDOWN - Azureus core pauseDownloads()!");
						AzureusStarter.getAzureusCore().getGlobalManager().pauseDownloads();
						AzureusStarter.getAzureusCore().stop();
					}*/

        // TODO:BITTORRENT
        // see Session.abort()
        /*
        if (AzureusStarter.isAzureusCoreStarted()) {
            System.out.println("Waiting for Vuze core to shutdown...");
            AzureusStarter.getAzureusCore().stop();
            System.out.println("Vuze core shutdown.");
        }*/
    }

    public boolean isStarted() {
        return true;
    }

    public boolean isFirewalled() {
        return isFirewalled;
    }

    public long getDownloadRate() {
        return session.getStatus().getDownloadRate();
    }

    public long getUploadRate() {
        return session.getStatus().getUploadRate();
    }

    public long getTotalDownload() {
        return session.getStatus().getTotalDownload();
    }

    public long getTotalUpload() {
        return session.getStatus().getTotalUpload();
    }

    public int getDownloadRateLimit() {
        return session.getSettings().getDownloadRateLimit();
    }

    public int getUploadRateLimit() {
        return session.getSettings().getDownloadRateLimit();
    }

    public void revertToDefaultConfiguration() {
        if (OSUtils.isAndroid()) {
            // need to test
            session.setSettings(SessionSettings.newMinMemoryUsage());
        } else {
            session.setSettings(SessionSettings.newDefaults());
        }
        saveSettings();
    }

    private void addEngineListener() {
        session.addListener(new AlertListener() {
            @Override
            public void alert(Alert<?> alert) {
                //LOG.info(a.message());
                if (listener == null) {
                    return;
                }

                AlertType type = alert.getType();

                switch (type) {
                    case TORRENT_ADDED:
                        listener.downloadAdded(new BTDownload(((TorrentAlert<?>) alert).getHandle()));
                        doResumeData((TorrentAlert<?>) alert);
                        break;
                    case SAVE_RESUME_DATA:
                        saveResumeData((SaveResumeDataAlert) alert);
                        break;
                    case BLOCK_FINISHED:
                        doResumeData((TorrentAlert<?>) alert);
                        break;
                    case PORTMAP:
                        isFirewalled = false;
                        break;
                    case PORTMAP_ERROR:
                        isFirewalled = true;
                        break;
                }
            }
        });
    }

    private File saveTorrent(TorrentInfo ti) {
        File torrentFile = null;

        try {
            String name = ti.getName();
            if (name == null || name.length() == 0) {
                name = ti.getInfoHash().toString();
            }

            torrentFile = new File(ctx.torrentsDir, name + ".torrent");
            byte[] arr = ti.toEntry().bencode();

            FileUtils.writeByteArrayToFile(torrentFile, arr);
        } catch (Throwable e) {
            torrentFile = null;
            LOG.warn("Error saving torrent info to file", e);
        }

        return torrentFile;
    }

    private void saveResumeTorrent(File torrent) {
        try {
            TorrentInfo ti = new TorrentInfo(torrent);
            entry e = ti.toEntry().getSwig();
            e.dict().set("torrent_orig_path", new entry(torrent.getAbsolutePath()));
            byte[] arr = Vectors.char_vector2bytes(e.bencode());
            FileUtils.writeByteArrayToFile(resumeTorrentFile(ti.getInfoHash().toString()), arr);
        } catch (Throwable e) {
            LOG.warn("Error saving resume torrent", e);
        }
    }

    private void saveResumeData(SaveResumeDataAlert alert) {
        try {
            TorrentHandle th = alert.getHandle();
            entry d = alert.getResumeData();
            byte[] arr = Vectors.char_vector2bytes(d.bencode());
            FileUtils.writeByteArrayToFile(resumeDataFile(th.getInfoHash().toString()), arr);
        } catch (Throwable e) {
            LOG.warn("Error saving resume data", e);
        }
    }

    private void doResumeData(TorrentAlert<?> alert) {
        TorrentHandle th = alert.getHandle();
        if (th.needSaveResumeData()) {
            th.saveResumeData();
        }
    }

    File resumeTorrentFile(String infoHash) {
        return new File(ctx.homeDir, infoHash + ".torrent");
    }

    File resumeDataFile(String infoHash) {
        return new File(ctx.homeDir, infoHash + ".resume");
    }

    private File stateFile() {
        return new File(ctx.homeDir, "settings.dat");
    }

    File readTorrentPath(String infoHash) {
        File torrent = null;

        try {
            byte[] arr = FileUtils.readFileToByteArray(resumeTorrentFile(infoHash));
            entry e = entry.bdecode(Vectors.bytes2char_vector(arr));
            torrent = new File(e.dict().get("torrent_orig_path").string());
        } catch (Throwable e) {
            // can't recover original torrent path
        }

        return torrent;
    }

    public void saveSettings() {
        byte[] data = session.saveState();
        try {
            FileUtils.writeByteArrayToFile(stateFile(), data);
        } catch (Throwable e) {
            LOG.error("Error saving session state", e);
        }
    }

    public void loadSettings() {
        try {
            File f = stateFile();
            if (f.exists()) {
                byte[] data = FileUtils.readFileToByteArray(f);
                session.loadState(data);
            } else {
                revertToDefaultConfiguration();
            }
        } catch (IOException e) {
            LOG.error("Error loading session state", e);
        }
    }
}
