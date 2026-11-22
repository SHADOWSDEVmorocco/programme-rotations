/*
 * ProgrammeRotations.exe – lanceur Windows.
 * Démarre l'application Java avec le Java fourni (dossier « runtime »), sans fenêtre console.
 * Si le dossier « runtime » est absent, utilise le Java installé sur le PC (javaw.exe dans le PATH).
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shlwapi.h>
#include <stdio.h>

static void error_box(const wchar_t *msg) {
    MessageBoxW(NULL, msg, L"Programme des Rotations", MB_ICONERROR | MB_OK);
}

int WINAPI wWinMain(HINSTANCE hInst, HINSTANCE hPrev, LPWSTR cmdLine, int show) {
    wchar_t dir[MAX_PATH], javaw[MAX_PATH], jar[MAX_PATH], cmd[4 * MAX_PATH + 512];
    if (!GetModuleFileNameW(NULL, dir, MAX_PATH)) { error_box(L"Chemin du programme introuvable."); return 1; }
    PathRemoveFileSpecW(dir);

    swprintf(jar, MAX_PATH, L"%ls\\app\\ProgrammeRotations.jar", dir);
    if (!PathFileExistsW(jar)) {
        error_box(L"Fichier manquant : app\\ProgrammeRotations.jar\n\nGardez ProgrammeRotations.exe dans son dossier "
                  L"(avec les dossiers « app » et « runtime »).");
        return 1;
    }
    swprintf(javaw, MAX_PATH, L"%ls\\runtime\\bin\\javaw.exe", dir);
    if (!PathFileExistsW(javaw)) wcscpy(javaw, L"javaw.exe");   /* Java installé sur le PC */

    swprintf(cmd, sizeof(cmd) / sizeof(cmd[0]),
             L"\"%ls\" -Xmx768m -Dfile.encoding=UTF-8 -Dsun.java2d.d3d=false -Djava.net.preferIPv4Stack=true "
             L"--enable-native-access=ALL-UNNAMED -jar \"%ls\" %ls",
             javaw, jar, cmdLine ? cmdLine : L"");

    STARTUPINFOW si; PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si)); si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));
    if (!CreateProcessW(NULL, cmd, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) {
        wchar_t msg[600];
        swprintf(msg, 600, L"Impossible de démarrer Java (code %lu).\n\nLe dossier « runtime » doit se trouver à côté de "
                 L"ProgrammeRotations.exe.", GetLastError());
        error_box(msg);
        return 1;
    }
    /* attendre 8 s : si Java s'arrête tout de suite, afficher l'erreur */
    if (WaitForSingleObject(pi.hProcess, 8000) == WAIT_OBJECT_0) {
        DWORD code = 0;
        GetExitCodeProcess(pi.hProcess, &code);
        if (code != 0) {
            wchar_t msg[600];
            swprintf(msg, 600, L"Le programme s'est arrêté (code %lu).\nDétails : %%APPDATA%%\\ProgrammeRotations\\journal.log", code);
            error_box(msg);
        }
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}
