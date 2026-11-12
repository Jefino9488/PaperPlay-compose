package io.max.paperplay.desktop

import java.awt.FileDialog
import java.io.File

/** The system's own file dialogs (the Windows common dialog, as every other app shows), not Swing's. */
internal object NativeDialogs {
    /** Files to open. [patterns] like `*.zip;*.png`; Windows applies them as the dialog's file filter. */
    fun openFiles(title: String, patterns: String?, multiple: Boolean = false): List<File> {
        val d = FileDialog(null as java.awt.Frame?, title, FileDialog.LOAD)
        d.isMultipleMode = multiple
        if (patterns != null) d.file = patterns
        d.isVisible = true
        return d.files.toList()
    }

    /** A place to save to, or null when cancelled. */
    fun saveFile(title: String, name: String): File? {
        val d = FileDialog(null as java.awt.Frame?, title, FileDialog.SAVE)
        d.file = name
        d.isVisible = true
        return d.files.firstOrNull()
    }

    /** A folder, from the system's folder picker (IFileOpenDialog), or null when cancelled. Blocks until closed. */
    fun pickFolder(title: String): File? {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            val d = FileDialog(null as java.awt.Frame?, title, FileDialog.LOAD)
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            d.isVisible = true
            return d.files.firstOrNull()
        }
        val script = FOLDER_SCRIPT.replace("__TITLE__", title.replace("'", "''"))
        return runCatching {
            val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-EncodedCommand",
                java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().lines().lastOrNull { it.isNotBlank() }?.trim()
            p.waitFor()
            out?.takeIf { File(it).isDirectory }?.let(::File)
        }.getOrNull()
    }

    private val FOLDER_SCRIPT = """
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class FolderPick {
  [ComImport, Guid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7")] class FileOpenDialog {}
  [ComImport, Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IShellItem {
    void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
    void GetParent(out IShellItem ppsi);
    void GetDisplayName(uint sigdnName, [MarshalAs(UnmanagedType.LPWStr)] out string name);
    void GetAttributes(uint mask, out uint attrs);
    void Compare(IShellItem psi, uint hint, out int order);
  }
  [ComImport, Guid("42f85136-db7e-439c-85f1-e4075d135fc8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IFileDialog {
    [PreserveSig] int Show(IntPtr hwnd);
    void SetFileTypes(uint c, IntPtr t);
    void SetFileTypeIndex(uint i);
    void GetFileTypeIndex(out uint i);
    void Advise(IntPtr p, out uint cookie);
    void Unadvise(uint cookie);
    void SetOptions(uint o);
    void GetOptions(out uint o);
    void SetDefaultFolder(IShellItem s);
    void SetFolder(IShellItem s);
    void GetFolder(out IShellItem s);
    void GetCurrentSelection(out IShellItem s);
    void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string n);
    void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string n);
    void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string t);
    void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string t);
    void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string t);
    void GetResult(out IShellItem s);
  }
  public static string Pick(string title) {
    var d = (IFileDialog)new FileOpenDialog();
    uint o; d.GetOptions(out o);
    d.SetOptions(o | 0x20 | 0x40);
    d.SetTitle(title);
    if (d.Show(IntPtr.Zero) != 0) return "";
    IShellItem r; d.GetResult(out r);
    string path; r.GetDisplayName(0x80058000, out path);
    return path;
  }
}
'@
[FolderPick]::Pick('__TITLE__')
""".trimIndent()
}
