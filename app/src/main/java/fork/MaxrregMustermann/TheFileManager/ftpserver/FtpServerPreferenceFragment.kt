/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.ftpserver

import android.os.Bundle
import fork.MaxrregMustermann.TheFileManager.R
import fork.MaxrregMustermann.TheFileManager.ui.PreferenceFragmentCompat

class FtpServerPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.ftp_server)
    }
}
