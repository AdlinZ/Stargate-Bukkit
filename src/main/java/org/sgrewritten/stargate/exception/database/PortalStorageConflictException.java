package org.sgrewritten.stargate.exception.database;

/** A portal identity was already claimed in shared storage. */
public class PortalStorageConflictException extends StorageWriteException {
    public PortalStorageConflictException(Throwable cause) { super(cause); }
}
