/*
 * Copyright (c) 2009-2011 by Christian Lorenz, Bjoern Kolbeck,
                              Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

package org.xtreemfs.osd.storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.Stack;

import org.xtreemfs.common.xloc.StripingPolicyImpl;
import org.xtreemfs.foundation.LRUCache;
import org.xtreemfs.foundation.buffer.BufferPool;
import org.xtreemfs.foundation.buffer.ReusableBuffer;
import org.xtreemfs.foundation.checksums.ChecksumAlgorithm;
import org.xtreemfs.foundation.checksums.ChecksumFactory;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.logging.Logging.Category;
import org.xtreemfs.foundation.util.OutputUtils;
import org.xtreemfs.osd.OSDConfig;
import org.xtreemfs.osd.replication.ObjectSet;
import org.xtreemfs.osd.storage.FileVersionLog.FileVersion;
import org.xtreemfs.osd.storage.VersionManager.ObjectVersionInfo;
import org.xtreemfs.pbrpc.generatedinterfaces.OSD.TruncateLog;

/**
 * 
 * @author clorenz
 */
public class HashStorageLayout extends StorageLayout {

    /**
     * file to store the truncate epoch in (metadata)
     */
    public static final String             TEPOCH_FILENAME       = ".tepoch";

    /**
     * File to store the master epoch.
     */
    public static final String             MASTER_EPOCH_FILENAME = ".mepoch";

    public static final String             TRUNCATE_LOG_FILENAME = ".tlog";

    /**
     * file that stores the mapping between file and object versions
     */
    public static final String             VLOG_FILENAME         = ".vlog";

    public static final int                SL_TAG                = 0x00000002;

    /** 32bit algorithm */
    public static final String             JAVA_HASH             = "Java-Hash";

    /** 64bit algorithm */
    public static final String             SDBM_HASH             = "SDBM";

    public static final int                SUBDIRS_16            = 15;

    public static final int                SUBDIRS_256           = 255;

    public static final int                SUBDIRS_4096          = 4095;

    public static final int                SUBDIRS_65535         = 65534;

    public static final int                SUBDIRS_1048576       = 1048575;

    public static final int                SUBDIRS_16777216      = 16777215;

    public static final String             DEFAULT_HASH          = JAVA_HASH;

    private static final int               DEFAULT_SUBDIRS       = SUBDIRS_256;

    private static final int               DEFAULT_MAX_DIR_DEPTH = 4;

    private int                            prefixLength;

    private int                            hashCutLength;

    private ChecksumAlgorithm              checksumAlgo;

    private long                           _stat_fileInfoLoads;

    private final boolean                  checksumsEnabled;

    private final LRUCache<String, String> hashedPathCache;

    private static final boolean           USE_PATH_CACHE        = true;

    /** Creates a new instance of HashStorageLayout */
    public HashStorageLayout(OSDConfig config, MetadataCache cache) throws IOException {
        this(config, cache, DEFAULT_HASH, DEFAULT_SUBDIRS, DEFAULT_MAX_DIR_DEPTH);
    }

    /**
     * Creates a new instance of HashStorageLayout. If some value is incorrect, the default value will be
     * used.
     * 
     * @param config
     * @param hashAlgo
     * @param maxSubdirsPerDir
     * @param maxDirDepth
     * @throws IOException
     */
    public HashStorageLayout(OSDConfig config, MetadataCache cache, String hashAlgo, int maxSubdirsPerDir,
            int maxDirDepth) throws IOException {

        super(config, cache);

        /*
         * if (hashAlgo.equals(JAVA_HASH)) { this.hashAlgo = new JavaHash(); }else if
         * (hashAlgo.equals(SDBM_HASH)) { this.hashAlgo = new SDBM(); }
         */

        this.checksumsEnabled = config.isUseChecksums();
        if (config.isUseChecksums()) {

            // get the algorithm from the factory
            try {
                checksumAlgo = ChecksumFactory.getInstance().getAlgorithm(config.getChecksumProvider());
                if (checksumAlgo == null)
                    throw new NoSuchAlgorithmException("algo is null");
            } catch (NoSuchAlgorithmException e) {
                Logging.logMessage(Logging.LEVEL_ERROR, Category.storage, this,
                        "could not instantiate checksum algorithm '%s'", config.getChecksumProvider());
                Logging.logMessage(Logging.LEVEL_ERROR, Category.storage, this, "OSD checksums will be switched off");
            }
        }

        if (maxSubdirsPerDir != 0) {
            this.prefixLength = Integer.toHexString(maxSubdirsPerDir).length();
        } else {
            this.prefixLength = Integer.toHexString(DEFAULT_SUBDIRS).length();
        }

        if (maxDirDepth != 0) {
            this.hashCutLength = maxDirDepth * this.prefixLength;
        } else {
            this.hashCutLength = DEFAULT_MAX_DIR_DEPTH * this.prefixLength;
        }

        if (Logging.isDebug()) {
            Logging.logMessage(Logging.LEVEL_DEBUG, this, "initialized with checksums=%s prefixLen=%d",
                    this.checksumsEnabled, this.prefixLength);
        }

        _stat_fileInfoLoads = 0;

        hashedPathCache = new LRUCache<String, String>(2048);
    }

    @Override
    public ObjectInformation readObject(String fileId, FileMetadata md, long objNo, int offset, int length,
            ObjectVersionInfo version) throws IOException {

        final int stripeSize = md.getStripingPolicy().getStripeSizeForObject(objNo);
        if (Logging.isDebug()) {
            Logging.logMessage(Logging.LEVEL_DEBUG, Category.storage, this, "fetching object %s-%d from disk", fileId,
                    objNo);
        }

        ReusableBuffer bbuf = null;

        if (length == -1) {
            assert (offset == 0) : "if length is -1 offset must be 0 but is " + offset;
            length = stripeSize;
        }

        // check if object does not exist
        if (version.version == 0) {
            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, Category.storage, this,
                        "object does not exist (according to md cache)");
            }
            return new ObjectInformation(ObjectInformation.ObjectStatus.DOES_NOT_EXIST, null, stripeSize);
        }

        // determine file name for version
        final long oldChecksum = md.getVersionManager().getObjectVersionInfo(objNo, version.version, version.timestamp).checksum;
        String fileName = generateAbsoluteObjectPathFromFileId(fileId, objNo, version.version, version.timestamp,
                oldChecksum);

        if (Logging.isDebug()) {
            Logging.logMessage(Logging.LEVEL_DEBUG, Category.storage, this, "path to object on disk: %s", fileName);
        }

        File file = new File(fileName);
        if (file.exists()) {

            RandomAccessFile f = new RandomAccessFile(file, "r");

            final int flength = (int) f.length();

            try {
                if (flength == 0) {

                    if (Logging.isDebug()) {
                        Logging.logMessage(Logging.LEVEL_DEBUG, Category.storage, this,
                                "object %d is a padding object", objNo);
                    }

                    return new ObjectInformation(ObjectInformation.ObjectStatus.PADDING_OBJECT, null, stripeSize);

                } else if (flength <= offset) {

                    bbuf = BufferPool.allocate(0);

                    if (Logging.isDebug()) {
                        Logging.logMessage(Logging.LEVEL_DEBUG, Category.storage, this,
                                "object %d is read at an offset beyond its size", objNo);
                    }

                    return new ObjectInformation(ObjectInformation.ObjectStatus.EXISTS, bbuf, stripeSize);

                } else {

                    // read object data
                    int lastoffset = offset + length;
                    assert (lastoffset <= stripeSize);

                    if (lastoffset > flength) {
                        assert (flength - offset > 0);
                        bbuf = BufferPool.allocate(flength - offset);
                    } else {
                        bbuf = BufferPool.allocate(length);
                    }

                    f.getChannel().position(offset);
                    f.getChannel().read(bbuf.getBuffer());

                    if (Logging.isDebug()) {
                        Logging.logMessage(Logging.LEVEL_DEBUG, Category.storage, this,
                                "object %d is read at offset %d, %d bytes read", objNo, offset, bbuf.limit());
                    }

                    assert (!bbuf.hasRemaining());
                    assert (bbuf.remaining() <= length);
                    bbuf.position(0);
                    f.close();
                    return new ObjectInformation(ObjectInformation.ObjectStatus.EXISTS, bbuf, stripeSize);
                }
            } finally {
                f.close();
            }

        } else {

            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, Category.storage, this, "object %d does not exist", objNo);
            }

            return new ObjectInformation(ObjectInformation.ObjectStatus.DOES_NOT_EXIST, null, stripeSize);
        }
    }

    @Override
    public void writeObject(String fileId, FileMetadata md, ReusableBuffer data, long objNo, int offset,
            long newVersion, long newTimestamp, boolean sync, CowPolicy cowPolicy) throws IOException {

        assert (newVersion > 0) : "object version must be > 0";

        if (data.capacity() == 0) {
            return;
        }

        String relPath = generateRelativeFilePath(fileId);
        new File(this.storageDir + relPath).mkdirs();

        if (Logging.isDebug()) {
            Logging.logMessage(Logging.LEVEL_DEBUG, Category.storage, this, "writing object %s-%d to disk: %s", fileId,
                    objNo, relPath);
        }

        // determine the object version to write
        final boolean isCow = cowPolicy.isCOW((int) objNo);

        try {

            final boolean isRangeWrite = (offset > 0)
                    || (data.capacity() < md.getStripingPolicy().getStripeSizeForObject(objNo));
            if (isRangeWrite) {
                if (isCow || checksumsEnabled) {
                    partialWriteCOW(relPath, fileId, md, data, offset, objNo, newVersion, newTimestamp, sync, !isCow);
                } else {
                    partialWriteNoCOW(relPath, fileId, md, data, objNo, offset, newVersion, newTimestamp, sync,
                            cowPolicy.cowEnabled());
                }
            } else {
                completeWrite(relPath, fileId, md, data, objNo, newVersion, newTimestamp, sync, !isCow);
            }

            // mark object as changed (may affect future COW)
            if (isCow)
                cowPolicy.objectChanged((int) objNo);

        } catch (FileNotFoundException ex) {
            throw new IOException("unable to create file directory or object: " + ex.getMessage());
        }

    }

    private void partialWriteCOW(String relativePath, String fileId, FileMetadata md, ReusableBuffer data, int offset,
            long objNo, long newVersion, long newTimestamp, boolean sync, boolean deleteOldVersion) throws IOException {

        assert (data != null);

        final ObjectVersionInfo oldVersion = md.getVersionManager().getLatestObjectVersionBefore(objNo, Long.MAX_VALUE,
                md.getLastObjectNumber() + 1);

        // perform COW
        ReusableBuffer fullObj = cow(fileId, md, objNo, data, offset, oldVersion);

        // calculate checksum
        final long newChecksum = calcChecksum(fullObj.getBuffer());
        final String newFilename = generateAbsoluteObjectPathFromRelPath(relativePath, objNo, newVersion, newTimestamp,
                newChecksum);
        if (Logging.isDebug()) {
            Logging.logMessage(Logging.LEVEL_DEBUG, this, "writing to file (COW): %s", newFilename);
        }
        File file = new File(newFilename);
        String mode = sync ? "rwd" : "rw";
        RandomAccessFile f = null;

        try {
            f = new RandomAccessFile(file, mode);
            fullObj.position(0);
            f.getChannel().write(fullObj.getBuffer());
        } finally {
            if (f != null) {
                f.close();
            }
        }

        // delete old version if required (e.g., if checksums w/o COW are
        // enabled)
        if (deleteOldVersion) {
            String oldFilename = generateAbsoluteObjectPathFromRelPath(relativePath, objNo, oldVersion.version,
                    oldVersion.timestamp, oldVersion.checksum);
            File oldFile = new File(oldFilename);
            oldFile.delete();

            md.getVersionManager().removeObjectVersionInfo(objNo, oldVersion.version, oldVersion.timestamp);
        }

        // update version info in metadata
        md.getVersionManager().addObjectVersionInfo(objNo, newVersion, newTimestamp, newChecksum);

        BufferPool.free(fullObj);
    }

    private void partialWriteNoCOW(String relativePath, String fileId, FileMetadata md, ReusableBuffer data,
            long objNo, int offset, long newVersion, long newTimestamp, boolean sync, boolean cowEnabled)
            throws IOException {
        // write file
        assert (!checksumsEnabled);

        // If COW is generally enabled, it is necessary to retrieve the latest object version, which may be
        // non-existent e.g. if the file has been truncated in the meantime. Otherwise, it is sufficient to
        // retrieve the largest known object version.
        final ObjectVersionInfo oldVersion = cowEnabled ? md.getVersionManager().getLatestObjectVersionBefore(objNo,
                Long.MAX_VALUE, md.getLastObjectNumber() + 1) : md.getVersionManager().getLargestObjectVersion(objNo);
        final String filename = generateAbsoluteObjectPathFromRelPath(relativePath, objNo, oldVersion.version,
                oldVersion.timestamp, oldVersion.checksum);
        if (Logging.isDebug()) {
            Logging.logMessage(Logging.LEVEL_DEBUG, this, "writing to file: %s", filename);
        }
        File file = new File(filename);
        String mode = sync ? "rwd" : "rw";
        RandomAccessFile f = null;

        try {
            f = new RandomAccessFile(file, mode);
            data.position(0);
            f.seek(offset);
            f.getChannel().write(data.getBuffer());
        } finally {
            if (f != null) {
                f.close();
            }
            BufferPool.free(data);
        }

        if (newTimestamp != oldVersion.timestamp || newVersion != oldVersion.version) {

            // checksum is always zero
            String newFilename = generateAbsoluteObjectPathFromRelPath(relativePath, objNo, newVersion, newTimestamp, 0);
            file.renameTo(new File(newFilename));
            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, this, "renamed to: %s", newFilename);
            }

            // update version info in metadata
            md.getVersionManager().removeObjectVersionInfo(objNo, oldVersion.version, oldVersion.timestamp);
            md.getVersionManager().addObjectVersionInfo(objNo, newVersion, newTimestamp, 0);
        }
    }

    private void completeWrite(String relativePath, String fileId, FileMetadata md, ReusableBuffer data, long objNo,
            long newVersion, long newTimestamp, boolean sync, boolean deleteOldVersion) throws IOException {
        // write file

        final ObjectVersionInfo oldVersion = md.getVersionManager().getLargestObjectVersion(objNo);

        final long newChecksum = calcChecksum(data.getBuffer());
        final String newFilename = generateAbsoluteObjectPathFromRelPath(relativePath, objNo, newVersion, newTimestamp,
                newChecksum);
        if (Logging.isDebug()) {
            Logging.logMessage(Logging.LEVEL_DEBUG, this, "writing to file: %s", newFilename);
        }
        File file = new File(newFilename);
        String mode = sync ? "rwd" : "rw";
        RandomAccessFile f = null;

        try {
            f = new RandomAccessFile(file, mode);
            data.position(0);
            f.getChannel().write(data.getBuffer());
        } finally {
            if (f != null) {
                f.close();
            }
            BufferPool.free(data);
        }

        if (deleteOldVersion
                && (newVersion != oldVersion.version || newTimestamp != oldVersion.timestamp || newChecksum != oldVersion.checksum)) {
            String oldFilename = generateAbsoluteObjectPathFromRelPath(relativePath, objNo, oldVersion.version,
                    oldVersion.timestamp, oldVersion.checksum);
            File oldFile = new File(oldFilename);
            oldFile.delete();

            md.getVersionManager().removeObjectVersionInfo(objNo, oldVersion.version, oldVersion.timestamp);
        }

        // update version info in metadata
        md.getVersionManager().addObjectVersionInfo(objNo, newVersion, newTimestamp, newChecksum);
    }

    @Override
    public void truncateObject(String fileId, FileMetadata md, long objNo, int newLength, long newVersion,
            long newTimestamp, boolean cow) throws IOException {

        assert (newLength <= md.getStripingPolicy().getStripeSizeForObject(objNo));

        final ObjectVersionInfo oldVersion = md.getVersionManager().getLargestObjectVersion(objNo);

        String oldFileName = generateAbsoluteObjectPathFromFileId(fileId, objNo, oldVersion.version,
                oldVersion.timestamp, oldVersion.checksum);
        File oldFile = new File(oldFileName);
        final long currentLength = oldFile.length();

        String mode = "rw";

        if (newLength == currentLength) {
            return;
        }

        if (cow || checksumsEnabled) {
            ReusableBuffer oldData = unwrapObjectData(fileId, md, objNo, oldVersion);

            if (newLength < oldData.capacity()) {
                oldData.range(0, newLength);
            } else {
                ReusableBuffer newData = BufferPool.allocate(newLength);
                newData.put(oldData);
                while (newData.hasRemaining()) {
                    newData.put((byte) 0);
                }
                BufferPool.free(oldData);
                oldData = newData;
            }
            oldData.position(0);

            long newChecksum = calcChecksum(oldData.getBuffer());

            if (!cow) {
                oldFile.delete();
                if (Logging.isDebug()) {
                    Logging.logMessage(Logging.LEVEL_DEBUG, Category.storage, this,
                            "truncate object %d, delete old version %d: %s", objNo, oldVersion.version, oldFileName);
                }
            }

            String newFilename = generateAbsoluteObjectPathFromFileId(fileId, objNo, newVersion, newTimestamp,
                    newChecksum);
            RandomAccessFile raf = null;
            try {
                raf = new RandomAccessFile(newFilename, mode);
                raf.getChannel().write(oldData.getBuffer());
            } finally {
                if (raf != null) {
                    raf.close();
                }
                BufferPool.free(oldData);
            }

            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, Category.storage, this,
                        "truncate object %d, wrote new version %d: %s", objNo, newVersion, newFilename);
            }

            // update version info in metadata
            md.getVersionManager().addObjectVersionInfo(objNo, newVersion, newTimestamp, newChecksum);

        } else {
            // just make the object shorter
            RandomAccessFile raf = null;
            try {
                raf = new RandomAccessFile(oldFile, mode);
                raf.setLength(newLength);
            } finally {
                if (raf != null) {
                    raf.close();
                }
            }

            if (newVersion != oldVersion.version || newTimestamp != oldVersion.timestamp) {

                String newFilename = generateAbsoluteObjectPathFromFileId(fileId, objNo, newVersion, newTimestamp, 0l);
                oldFile.renameTo(new File(newFilename));

                if (Logging.isDebug()) {
                    Logging.logMessage(Logging.LEVEL_DEBUG, Category.storage, this,
                            "truncate object %d, renamed file for new version %d: %s", objNo, newVersion, newFilename);
                }

                // update version info in metadata
                md.getVersionManager().removeObjectVersionInfo(objNo, newVersion, newTimestamp);
                md.getVersionManager().addObjectVersionInfo(objNo, newVersion, newTimestamp, 0);
            }
        }
    }

    @Override
    public void createPaddingObject(String fileId, FileMetadata md, long objNo, long version, long timestamp, int size)
            throws IOException {

        assert (size >= 0) : "size is " + size;

        String relPath = generateRelativeFilePath(fileId);
        new File(this.storageDir + relPath).mkdirs();

        // calculate the checksum for the padding object if necessary
        long checksum = calcChecksum(ByteBuffer.wrap(new byte[(int) size]));

        // write file
        String filename = generateAbsoluteObjectPathFromRelPath(relPath, objNo, version, timestamp, checksum);
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(filename, "rw");
            raf.setLength(size);
        } finally {
            if (raf != null) {
                raf.close();
            }
        }

        // update version info in metadata
        md.getVersionManager().addObjectVersionInfo(objNo, version, timestamp, checksum);
    }

    @Override
    public void deleteFile(String fileId, boolean deleteMetadata) throws IOException {

        File fileDir = new File(generateAbsoluteFilePath(fileId));
        File[] objs = fileDir.listFiles();

        if (objs == null) {
            return;
        }

        // otherwise, delete the file including its metadata
        else {

            for (File obj : objs) {
                obj.delete();
            }

            // delete all empty dirs along the path
            if (deleteMetadata) {
                del(fileDir);
            }

        }
    }

    private void del(File parent) {
        if (parent.list().length > 1 || parent.equals(new File(this.storageDir))) {
            return;
        } else {
            assert (parent != null);
            parent.delete();
            del(parent.getParentFile());
        }
    }

    @Override
    public void deleteObject(String fileId, FileMetadata md, final long objNo, final long version, final long timestamp)
            throws IOException {

        // retrieve information about version to delete
        // if the version number is zero, the largest local version will be deleted
        // if the timestamp is zero and multiple objects w/ different timestamps exist, the object w/ the
        // largest timestamp will be deleted
        VersionManager vm = md.getVersionManager();
        final ObjectVersionInfo verToDel = (version == 0) ? vm.getLargestObjectVersion(objNo) : timestamp == 0 ? vm
                .getLargestObjectVersionBefore(objNo, version) : vm.getObjectVersionInfo(objNo, version, timestamp);

        // delete object version
        String filename = generateAbsoluteObjectPathFromFileId(fileId, objNo, verToDel.version, verToDel.timestamp,
                verToDel.checksum);
        new File(filename).delete();
    }

    @Override
    public boolean fileExists(String fileId) {
        File dir = new File(generateAbsoluteFilePath(fileId));
        return dir.exists();
    }

    protected FileMetadata loadFileMetadata(String fileId, StripingPolicyImpl sp) throws IOException {

        _stat_fileInfoLoads = 0;

        // get local directory for file
        File fileDir = new File(generateAbsoluteFilePath(fileId));

        // initialize file version log
        File logFile = new File(fileDir, VLOG_FILENAME);
        FileVersionLog vlog = new FileVersionLog(logFile);
        if (logFile.exists())
            vlog.load();

        FileMetadata md = new FileMetadata(sp, vlog, logFile.exists());
        VersionManager vm = md.getVersionManager();

        // file exists already ...
        if (fileDir.exists()) {

            // determine the largest object versions, as well as all checksums
            String[] objs = fileDir.list();
            for (String obj : objs) {

                if (obj.startsWith(".")) {
                    continue; // ignore special files (metadata, .tepoch, ...)
                }

                // parse version info from file name and add data to version
                // manager
                ObjFileData ofd = parseFileName(obj);
                md.getVersionManager().addObjectVersionInfo(ofd.objNo, ofd.objVersion, ofd.timestamp, ofd.checksum);
            }

            // read truncate epoch from file
            File tepoch = new File(fileDir, TEPOCH_FILENAME);
            if (tepoch.exists()) {
                RandomAccessFile raf = null;
                try {
                    raf = new RandomAccessFile(tepoch, "r");
                    md.setTruncateEpoch(raf.readLong());
                } finally {
                    if (raf != null) {
                        raf.close();
                    }
                }
            }

            // initialize file size and last object version number; if versioning is enabled, do this by means
            // of the latest version registered in the file version log
            if (vm.isVersioningEnabled()) {
                FileVersion fv = vm.getLatestFileVersionBefore(Long.MAX_VALUE);
                md.setFilesize(fv == null ? 0 : fv.getFileSize());
                md.setLastObjectNumber(fv == null ? -1 : fv.getNumObjects() - 1);
            }

            // if no versioning is enabled, determine object count and file size from the object with the
            // largest known object number
            else {

                long lastObjNum = md.getVersionManager().getLastObjectId();
                ObjectVersionInfo lastObj = vm.getLargestObjectVersion(lastObjNum);

                File lastObjFile = new File(fileDir.getAbsolutePath()
                        + "/"
                        + generateAbsoluteObjectPathFromFileId(fileId, lastObjNum, lastObj.version, lastObj.timestamp,
                                lastObj.checksum));
                long lastObjSize = lastObjFile.length();
                // check for empty padding file
                if (lastObjSize == 0) {
                    lastObjSize = sp.getStripeSizeForObject(lastObjSize);
                }
                long fsize = lastObjSize;
                if (lastObjNum > 0) {
                    fsize += sp.getObjectEndOffset(lastObjNum - 1) + 1;
                }
                assert (fsize >= 0);
                md.setFilesize(fsize);
                md.setLastObjectNumber(lastObjNum);
            }

        }

        // file does not exist
        else {
            md.setFilesize(0);
            md.setLastObjectNumber(-1);
        }

        md.setGlobalLastObjectNumber(-1);
        return md;
    }

    @Override
    public void setTruncateEpoch(String fileId, long newTruncateEpoch) throws IOException {
        File parent = new File(generateAbsoluteFilePath(fileId));
        if (!parent.exists()) {
            parent.mkdirs();
        }
        File tepoch = new File(parent, TEPOCH_FILENAME);
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(tepoch, "rw");
            raf.writeLong(newTruncateEpoch);
        } finally {
            if (raf != null) {
                raf.close();
            }
        }
    }

    // @Override
    // public ObjectSet getObjectSet(String fileId, FileMetadata md) {
    // ObjectSet objectSet;
    //
    // File fileDir = new File(generateAbsoluteFilePath(fileId));
    // if (fileDir.exists()) {
    // String[] objs = fileDir.list(new FilenameFilter() {
    //
    // @Override
    // public boolean accept(File dir, String name) {
    // if (name.startsWith(".")) // ignore special files (metadata,
    // // .tepoch)
    // {
    // return false;
    // } else {
    // return true;
    // }
    // }
    // });
    // objectSet = new ObjectSet(objs.length);
    //
    // for (int i = 0; i < objs.length; i++) {
    // objectSet.add(parseFileName(objs[i]).objNo);
    // }
    // } else {
    // objectSet = new ObjectSet(0);
    // }
    //
    // return objectSet;
    // }

    private String generateAbsoluteFilePath(String fileId) {
        return this.storageDir + generateRelativeFilePath(fileId);
    }

    private String generateAbsoluteObjectPathFromFileId(String fileId, long objNo, long version, long timestamp,
            long checksum) {
        StringBuilder path = new StringBuilder(generateAbsoluteFilePath(fileId));
        path.append(createFileName(objNo, version, timestamp, checksum));
        return path.toString();
    }

    private String generateAbsoluteObjectPathFromRelPath(String relativeFilePath, long objNo, long version,
            long timestamp, long checksum) {
        StringBuilder path = new StringBuilder(this.storageDir);
        path.append(relativeFilePath);
        path.append(createFileName(objNo, version, timestamp, checksum));
        return path.toString();
    }

    private String generateRelativeFilePath(String fileId) {
        if (USE_PATH_CACHE) {
            String cached = hashedPathCache.get(fileId);
            if (cached != null)
                return cached;
        }
        String id = (WIN) ? fileId.replace(':', '_') : fileId;
        StringBuilder path = generateHashPath(id);
        path.append(id);
        path.append("/");
        final String pathStr = path.toString();
        if (USE_PATH_CACHE) {
            hashedPathCache.put(fileId, pathStr);
        }
        return pathStr;
    }

    /**
     * generates the path for the file with an "/" at the end
     * 
     * @param fileId
     * @return
     */
    private StringBuilder generateHashPath(String fileId) {
        StringBuilder hashPath = new StringBuilder(128);
        String hash = hash(fileId);
        int i = 0, j = prefixLength;

        while (j < hash.length()) {
            hashPath.append(hash.subSequence(i, j));
            hashPath.append("/");

            i += prefixLength;
            j += prefixLength;
        }
        if (j < hash.length() + prefixLength) {
            hashPath.append(hash.subSequence(i, hash.length()));
            hashPath.append("/");
        }
        return hashPath;
    }

    /**
     * computes the hash for the File
     * 
     * @param str
     * @return
     */
    private String hash(String str) {
        assert (str != null);
        // this.hashAlgo.digest(str);
        StringBuffer sb = new StringBuffer(16);
        // final long hashValue = this.hashAlgo.getValue();
        final long hashValue = str.hashCode();
        OutputUtils.writeHexLong(sb, hashValue);

        if (sb.length() > this.hashCutLength) {
            return sb.substring(0, this.hashCutLength);
        } else {
            return sb.toString();
        }
    }

    public long getFileInfoLoadCount() {
        return _stat_fileInfoLoads;
    }

    /**
     * 
     * @param f
     * @return the VersionNo of the given File.
     * @throws NumberFormatException
     */
    private long getVersion(File f) throws NumberFormatException {
        final String name = f.getName();
        ObjFileData ofd = parseFileName(name);
        return ofd.objVersion;
    }

    /**
     * 
     * @param f
     * @return the ObjectNo of the given File.
     * @throws NumberFormatException
     */
    private long getObjectNo(File f) throws NumberFormatException {
        final String name = f.getName();
        ObjFileData ofd = parseFileName(name);
        return ofd.objNo;
    }

    public static String createFileName(long objNo, long objVersion, long timestamp, long checksum) {
        final StringBuffer sb = new StringBuffer(3 * Long.SIZE / 8);
        OutputUtils.writeHexLong(sb, objNo);
        OutputUtils.writeHexLong(sb, objVersion);
        OutputUtils.writeHexLong(sb, checksum);
        if (timestamp != -1)
            OutputUtils.writeHexLong(sb, timestamp);
        return sb.toString();
    }

    public static ObjFileData parseFileName(String filename) {
        if (filename.length() == 32) {
            // compatibility mode
            final long objNo = OutputUtils.readHexLong(filename, 0);
            final int objVersion = OutputUtils.readHexInt(filename, 16);
            final long checksum = OutputUtils.readHexLong(filename, 24);
            return new ObjFileData(objNo, objVersion, checksum, -1);
        } else if (filename.length() == 48) {
            // no COW versioning
            final long objNo = OutputUtils.readHexLong(filename, 0);
            final long objVersion = OutputUtils.readHexLong(filename, 16);
            final long checksum = OutputUtils.readHexLong(filename, 32);
            return new ObjFileData(objNo, objVersion, checksum, -1);
        } else {
            // COW versioning
            final long objNo = OutputUtils.readHexLong(filename, 0);
            final long objVersion = OutputUtils.readHexLong(filename, 16);
            final long checksum = OutputUtils.readHexLong(filename, 32);
            final long cowVersion = OutputUtils.readHexLong(filename, 48);
            return new ObjFileData(objNo, objVersion, checksum, cowVersion);
        }
    }

    @Override
    public int getLayoutVersionTag() {
        return SL_TAG;
    }

    @Override
    public boolean isCompatibleVersion(int layoutVersionTag) {
        if (layoutVersionTag == SL_TAG) {
            return true;
        }
        // we are compatible with the old layout (version was an int)
        if (layoutVersionTag == 1) {
            return true;
        }
        return false;
    }

    public static final class ObjFileData {

        final long objNo;

        final long objVersion;

        final long checksum;

        final long timestamp;

        public ObjFileData(long objNo, long objVersion, long checksum, long timestamp) {
            this.objNo = objNo;
            this.objVersion = objVersion;
            this.checksum = checksum;
            this.timestamp = timestamp;
        }
    }

    @Override
    public FileList getFileList(FileList l, int maxNumEntries) {

        if (l == null) {
            l = new FileList(new Stack<String>(), new HashMap<String, FileData>());
            l.status.push("");
        }
        l.files.clear();

        try {
            do {
                int PREVIEW_LENGTH = 15;
                String currentDir = l.status.pop();
                File dir = new File(storageDir + currentDir);
                if (dir.listFiles() == null) {
                    Logging.logMessage(Logging.LEVEL_WARN, Category.misc, this, storageDir + currentDir
                            + " is not a valid directory!");
                    continue;
                }

                FileReader fReader;

                File newestFirst = null;
                File newestLast = null;
                Long objectSize = 0L;

                for (File ch : dir.listFiles()) {
                    // handle the directories (hash and fileName)
                    if (ch != null && ch.isDirectory()) {
                        l.status.push(currentDir + "/" + ch.getName());
                        // get informations from the objects
                    } else if (ch != null && ch.isFile() && !ch.getName().contains(".")
                            && !ch.getName().endsWith(".ser")) {
                        // get the file metadata
                        try {
                            long version = getVersion(ch);
                            long objNum = getObjectNo(ch);

                            if (newestFirst == null) {

                                newestFirst = newestLast = ch;
                                objectSize = ch.length();
                            } else if (version > getVersion(newestFirst)) {

                                newestFirst = newestLast = ch;
                                objectSize = (objectSize >= ch.length()) ? objectSize : ch.length();
                            } else if (version == getVersion(newestFirst)) {

                                if (objNum < getObjectNo(newestFirst)) {
                                    newestFirst = ch;
                                } else if (objNum > getObjectNo(newestLast)) {
                                    newestLast = ch;
                                }
                                objectSize = (objectSize >= ch.length()) ? objectSize : ch.length();
                            }
                        } catch (Exception e) {

                            Logging.logMessage(Logging.LEVEL_WARN, Category.storage, this, "CleanUp: an illegal file ("
                                    + ch.getAbsolutePath() + ") was discovered and ignored.");
                        }
                    }
                }

                // dir is a fileName-directory
                if (newestFirst != null) {
                    // get a preview from the file
                    char[] preview = null;
                    try {
                        fReader = new FileReader(newestFirst);
                        preview = new char[PREVIEW_LENGTH];
                        fReader.read(preview);
                        fReader.close();
                    } catch (Exception e) {
                        assert (false);
                    }

                    // get the metaInfo from the root-directory
                    long stripCount = getObjectNo(newestLast);
                    long fileSize = (stripCount == 1) ? newestFirst.length() : (objectSize * stripCount)
                            + newestLast.length();

                    // insert the data into the FileList
                    l.files.put((WIN) ? dir.getName().replace('_', ':') : dir.getName(), new FileData(fileSize,
                            (int) (objectSize / 1024)));
                }
            } while (l.files.size() < maxNumEntries);
            l.hasMore = true;
            return l;

        } catch (EmptyStackException ex) {
            // done
            l.hasMore = false;
            return l;
        }
    }

    @Override
    public ArrayList<String> getFileIDList() {

        ArrayList<String> fileList = new ArrayList<String>();

        Stack<String> directories = new Stack<String>();
        directories.push(storageDir);

        File currentFile;

        while (!directories.empty()) {
            currentFile = new File(directories.pop());
            for (File f : currentFile.listFiles()) {
                if (f != null && f.isDirectory() && !f.getName().contains(":")) {
                    directories.push(f.getAbsolutePath());
                } else {
                    if (f != null && !f.getName().contains(".") && !f.getName().endsWith(".ser")) {
                        fileList.add(f.getName());
                    }
                }
            }
        }

        return fileList;
    }

    @Override
    public int getMasterEpoch(String fileId) throws IOException {
        int masterEpoch = 0;
        RandomAccessFile raf = null;
        try {
            File fileDir = new File(generateAbsoluteFilePath(fileId));
            File mepoch = new File(fileDir, MASTER_EPOCH_FILENAME);

            raf = new RandomAccessFile(mepoch, "r");
            masterEpoch = raf.readInt();
        } catch (FileNotFoundException ex) {
        } finally {
            if (raf != null) {
                raf.close();
            }
        }
        return masterEpoch;
    }

    public void setMasterEpoch(String fileId, int masterEpoch) throws IOException {
        File fileDir = new File(generateAbsoluteFilePath(fileId));
        if (!fileDir.exists()) {
            fileDir.mkdirs();
        }
        File mepoch = new File(fileDir, MASTER_EPOCH_FILENAME);
        RandomAccessFile rf = new RandomAccessFile(mepoch, "rw");
        rf.writeInt(masterEpoch);
        rf.close();
    }

    public TruncateLog getTruncateLog(String fileId) throws IOException {
        TruncateLog.Builder tlbuilder = TruncateLog.newBuilder();

        try {
            File fileDir = new File(generateAbsoluteFilePath(fileId));
            File tlog = new File(fileDir, TRUNCATE_LOG_FILENAME);
            FileInputStream input = null;
            try {
                input = new FileInputStream(tlog);
                tlbuilder.mergeDelimitedFrom(input);
            } finally {
                if (input != null) {
                    input.close();
                }
            }
        } catch (IOException ex) {
        }
        return tlbuilder.build();
    }

    public void setTruncateLog(String fileId, TruncateLog log) throws IOException {
        File fileDir = new File(generateAbsoluteFilePath(fileId));
        if (!fileDir.exists()) {
            fileDir.mkdirs();
        }
        File tlog = new File(fileDir, TRUNCATE_LOG_FILENAME);
        FileOutputStream output = null;

        try {
            output = new FileOutputStream(tlog);
            log.writeDelimitedTo(output);
        } finally {
            if (output != null) {
                output.close();
            }
        }
    }

    private long calcChecksum(ByteBuffer buf) {

        long newChecksum = 0;
        if (checksumsEnabled) {
            checksumAlgo.reset();
            checksumAlgo.update(buf);
            newChecksum = checksumAlgo.getValue();
        }

        return newChecksum;
    }

}
