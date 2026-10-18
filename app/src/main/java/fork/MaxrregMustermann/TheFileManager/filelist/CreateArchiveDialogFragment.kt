/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.filelist

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import androidx.annotation.StringRes
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.parcelize.Parcelize
import fork.MaxrregMustermann.TheFileManager.R
import fork.MaxrregMustermann.TheFileManager.databinding.CreateArchiveDialogBinding
import fork.MaxrregMustermann.TheFileManager.databinding.NameDialogNameIncludeBinding
import fork.MaxrregMustermann.TheFileManager.util.ParcelableArgs
import fork.MaxrregMustermann.TheFileManager.util.args
import fork.MaxrregMustermann.TheFileManager.util.putArgs
import fork.MaxrregMustermann.TheFileManager.util.setTextWithSelection
import fork.MaxrregMustermann.TheFileManager.util.show
import fork.MaxrregMustermann.TheFileManager.util.takeIfNotEmpty
import fork.MaxrregMustermann.TheFileManager.provider.archive.archiver.ArchiveFormat

class CreateArchiveDialogFragment : FileNameDialogFragment() {
    private val args by args<Args>()

    override val binding: Binding
        get() = super.binding as Binding

    override val listener: Listener
        get() = super.listener as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)

        if (savedInstanceState == null) {
            val files = args.files
            var name: String? = null
            if (files.size == 1) {
                name = files.single().path.fileName.toString()
            } else {
                val parent = files.mapTo(mutableSetOf()) { it.path.parent }.singleOrNull()
                if (parent != null && parent.nameCount > 0) {
                    name = parent.fileName.toString()
                }
            }
            name?.let { binding.nameEdit.setTextWithSelection(it) }
        }
        binding.typeGroup.setOnCheckedChangeListener { _, _ -> updatePasswordLayoutVisibility() }
        updatePasswordLayoutVisibility()
        return dialog
    }

    @StringRes
    override val titleRes: Int = R.string.file_create_archive_title

    override fun onInflateBinding(inflater: LayoutInflater): NameDialogFragment.Binding =
        Binding.inflate(inflater)

    override val name: String
        get() {
            val extension = when (val checkedId = binding.typeGroup.checkedRadioButtonId) {
                R.id.zipRadio -> "zip"
                R.id.tarXzRadio -> "tar.xz"
                R.id.sevenZRadio -> "7z"
                else -> throw AssertionError(checkedId)
            }
            return "${super.name}.$extension"
        }

    private val isPasswordSupported: Boolean
        get() = when (val checkedId = binding.typeGroup.checkedRadioButtonId) {
            R.id.zipRadio -> true
            R.id.tarXzRadio, R.id.sevenZRadio -> false
            else -> throw AssertionError(checkedId)
        }

    private fun updatePasswordLayoutVisibility() {
        binding.passwordLayout.isGone = !isPasswordSupported
    }

    override fun onOk(name: String) {
        val format = when (val checkedId = binding.typeGroup.checkedRadioButtonId) {
            R.id.zipRadio -> ArchiveFormat.ZIP
            R.id.tarXzRadio -> ArchiveFormat.TAR_XZ
            R.id.sevenZRadio -> ArchiveFormat.SEVEN_Z
            else -> throw AssertionError(checkedId)
        }
        val password = if (isPasswordSupported) {
            binding.passwordEdit.text!!.toString().takeIfNotEmpty()
        } else {
            null
        }
        listener.archive(args.files, name, format, password)
    }

    companion object {
        fun show(files: FileItemSet, fragment: Fragment) {
            CreateArchiveDialogFragment().putArgs(Args(files)).show(fragment)
        }
    }

    @Parcelize
    class Args(val files: FileItemSet) : ParcelableArgs

    protected class Binding private constructor(
        root: View,
        nameLayout: TextInputLayout,
        nameEdit: EditText,
        val typeGroup: RadioGroup,
        val passwordLayout: TextInputLayout,
        val passwordEdit: TextInputEditText
    ) : NameDialogFragment.Binding(root, nameLayout, nameEdit) {
        companion object {
            fun inflate(inflater: LayoutInflater): Binding {
                val binding = CreateArchiveDialogBinding.inflate(inflater)
                val bindingRoot = binding.root
                val nameBinding = NameDialogNameIncludeBinding.bind(bindingRoot)
                return Binding(
                    bindingRoot, nameBinding.nameLayout, nameBinding.nameEdit, binding.typeGroup,
                    binding.passwordLayout, binding.passwordEdit
                )
            }
        }
    }

    interface Listener : FileNameDialogFragment.Listener {
        fun archive(
            files: FileItemSet, name: String, format: ArchiveFormat, password: String?
        )
    }
}
