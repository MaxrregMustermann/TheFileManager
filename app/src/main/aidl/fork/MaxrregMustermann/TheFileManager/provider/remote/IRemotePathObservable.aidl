package fork.MaxrregMustermann.TheFileManager.provider.remote;

import fork.MaxrregMustermann.TheFileManager.provider.remote.ParcelableException;
import fork.MaxrregMustermann.TheFileManager.util.RemoteCallback;

interface IRemotePathObservable {
    void addObserver(in RemoteCallback observer);

    void close(out ParcelableException exception);
}
