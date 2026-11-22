/*
 * envoyer_mail.exe <fichier.pdf> <objet> <message>
 * Ouvre la fenêtre « Nouveau message » de la messagerie par défaut de Windows (Outlook, Thunderbird, …)
 * avec le fichier DÉJÀ JOINT (interface Simple MAPI, comme « Envoyer vers → Destinataire » de l'Explorateur).
 * Code de sortie : 0 = message ouvert/envoyé, 1 = annulé par l'utilisateur, 2+ = pas de messagerie compatible.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mapi.h>
#include <shellapi.h>
#include <shlwapi.h>
#include <stdlib.h>

/* Versions Unicode (Windows 8+), absentes de mapi.h de mingw : même disposition que le SDK Microsoft. */
typedef struct { ULONG ulReserved, ulRecipClass; PWSTR lpszName, lpszAddress; ULONG ulEIDSize; PVOID lpEntryID; } MapiRecipDescW;
typedef struct { ULONG ulReserved, flFlags, nPosition; PWSTR lpszPathName, lpszFileName; PVOID lpFileType; } MapiFileDescW;
typedef struct {
    ULONG ulReserved; PWSTR lpszSubject, lpszNoteText, lpszMessageType, lpszDateReceived, lpszConversationID;
    FLAGS flFlags; MapiRecipDescW *lpOriginator; ULONG nRecipCount; MapiRecipDescW *lpRecips;
    ULONG nFileCount; MapiFileDescW *lpFiles;
} MapiMessageW, *lpMapiMessageW;

typedef ULONG (WINAPI *SendW_t)(LHANDLE, ULONG_PTR, lpMapiMessageW, FLAGS, ULONG);
typedef ULONG (WINAPI *SendA_t)(LHANDLE, ULONG_PTR, lpMapiMessage, FLAGS, ULONG);

static char *to_ansi(const wchar_t *w) {
    int n = WideCharToMultiByte(CP_ACP, 0, w, -1, NULL, 0, NULL, NULL);
    char *s = (char *)malloc(n > 0 ? n : 1);
    if (n > 0) WideCharToMultiByte(CP_ACP, 0, w, -1, s, n, NULL, NULL); else s[0] = 0;
    return s;
}

int WINAPI wWinMain(HINSTANCE h, HINSTANCE p, LPWSTR cmd, int show) {
    int argc = 0;
    LPWSTR *argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (!argv || argc < 4) return 9;
    LPWSTR path = argv[1], subject = argv[2], body = argv[3];
    LPWSTR name = PathFindFileNameW(path);

    HMODULE mapi = LoadLibraryW(L"mapi32.dll");
    if (!mapi) return 3;
    ULONG rc = MAPI_E_NOT_SUPPORTED;

    SendW_t sendW = (SendW_t)GetProcAddress(mapi, "MAPISendMailW");
    if (sendW) {
        MapiFileDescW f; ZeroMemory(&f, sizeof f);
        f.nPosition = (ULONG)-1; f.lpszPathName = path; f.lpszFileName = name;
        MapiMessageW m; ZeroMemory(&m, sizeof m);
        m.lpszSubject = subject; m.lpszNoteText = body; m.nFileCount = 1; m.lpFiles = &f;
        rc = sendW(0, 0, &m, MAPI_LOGON_UI | MAPI_DIALOG, 0);
    }
    if (rc != SUCCESS_SUCCESS && rc != MAPI_USER_ABORT) {          /* anciennes messageries : version ANSI */
        SendA_t sendA = (SendA_t)GetProcAddress(mapi, "MAPISendMail");
        if (sendA) {
            char *pa = to_ansi(path), *na = to_ansi(name), *sa = to_ansi(subject), *ba = to_ansi(body);
            MapiFileDesc f; ZeroMemory(&f, sizeof f);
            f.nPosition = (ULONG)-1; f.lpszPathName = pa; f.lpszFileName = na;
            MapiMessage m; ZeroMemory(&m, sizeof m);
            m.lpszSubject = sa; m.lpszNoteText = ba; m.nFileCount = 1; m.lpFiles = &f;
            rc = sendA(0, 0, &m, MAPI_LOGON_UI | MAPI_DIALOG, 0);
        }
    }
    if (rc == SUCCESS_SUCCESS) return 0;
    if (rc == MAPI_USER_ABORT) return 1;
    return 2;
}
