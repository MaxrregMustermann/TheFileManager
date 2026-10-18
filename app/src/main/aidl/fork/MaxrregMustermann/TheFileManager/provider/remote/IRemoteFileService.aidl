package fork.MaxrregMustermann.TheFileManager.provider.remote;

import fork.MaxrregMustermann.TheFileManager.provider.remote.IRemoteFileSystem;
import fork.MaxrregMustermann.TheFileManager.provider.remote.IRemoteFileSystemProvider;
import fork.MaxrregMustermann.TheFileManager.provider.remote.IRemotePosixFileAttributeView;
import fork.MaxrregMustermann.TheFileManager.provider.remote.IRemotePosixFileStore;
import fork.MaxrregMustermann.TheFileManager.provider.remote.ParcelableObject;

interface IRemoteFileService {
    IRemoteFileSystemProvider getRemoteFileSystemProviderInterface(String scheme);

    IRemoteFileSystem getRemoteFileSystemInterface(in ParcelableObject fileSystem);

    IRemotePosixFileStore getRemotePosixFileStoreInterface(in ParcelableObject fileStore);

    IRemotePosixFileAttributeView getRemotePosixFileAttributeViewInterface(
        in ParcelableObject attributeView
    );
}
