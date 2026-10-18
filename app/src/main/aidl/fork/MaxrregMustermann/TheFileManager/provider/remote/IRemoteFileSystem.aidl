package fork.MaxrregMustermann.TheFileManager.provider.remote;

import fork.MaxrregMustermann.TheFileManager.provider.remote.ParcelableException;

interface IRemoteFileSystem {
    void close(out ParcelableException exception);
}
