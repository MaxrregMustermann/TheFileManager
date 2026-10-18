package fork.MaxrregMustermann.TheFileManager.provider.remote;

import fork.MaxrregMustermann.TheFileManager.provider.common.ParcelableFileTime;
import fork.MaxrregMustermann.TheFileManager.provider.common.ParcelablePosixFileMode;
import fork.MaxrregMustermann.TheFileManager.provider.common.PosixGroup;
import fork.MaxrregMustermann.TheFileManager.provider.common.PosixUser;
import fork.MaxrregMustermann.TheFileManager.provider.remote.ParcelableException;
import fork.MaxrregMustermann.TheFileManager.provider.remote.ParcelableObject;

interface IRemotePosixFileAttributeView {
    ParcelableObject readAttributes(out ParcelableException exception);

    void setTimes(
        in ParcelableFileTime lastModifiedTime,
        in ParcelableFileTime lastAccessTime,
        in ParcelableFileTime createTime,
        out ParcelableException exception
    );

    void setOwner(in PosixUser owner, out ParcelableException exception);

    void setGroup(in PosixGroup group, out ParcelableException exception);

    void setMode(in ParcelablePosixFileMode mode, out ParcelableException exception);

    void setSeLinuxContext(in ParcelableObject context, out ParcelableException exception);

    void restoreSeLinuxContext(out ParcelableException exception);
}
