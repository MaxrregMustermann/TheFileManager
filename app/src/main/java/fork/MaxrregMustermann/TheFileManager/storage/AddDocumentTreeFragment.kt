/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.storage

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import fork.MaxrregMustermann.TheFileManager.file.DocumentTreeUri
import fork.MaxrregMustermann.TheFileManager.file.asDocumentTreeUriOrNull
import fork.MaxrregMustermann.TheFileManager.file.takePersistablePermission
import fork.MaxrregMustermann.TheFileManager.util.finish
import fork.MaxrregMustermann.TheFileManager.util.launchSafe

class AddDocumentTreeFragment : Fragment() {
    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(), this::onOpenDocumentTreeResult
    )

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        if (savedInstanceState == null) {
            openDocumentTreeLauncher.launchSafe(null, this)
        }
    }

    private fun onOpenDocumentTreeResult(result: Uri?) {
        val treeUri = result?.asDocumentTreeUriOrNull()
        if (treeUri != null) {
            addDocumentTree(treeUri)
        }
        finish()
    }

    private fun addDocumentTree(treeUri: DocumentTreeUri) {
        treeUri.takePersistablePermission()
        val documentTree = DocumentTree(null, null, treeUri)
        Storages.addOrReplace(documentTree)
    }
}
