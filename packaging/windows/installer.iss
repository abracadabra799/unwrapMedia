[Setup]
AppId={{2D73CA07-DCD4-4B4E-B6A3-B9DA2BC41A9D}
AppName=unwrapMedia
AppVersion=1.12.0
DefaultDirName={userpf}\unwrapMedia
DefaultGroupName=unwrapMedia
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
CloseApplications=force
RestartApplications=no
OutputDir=Output
OutputBaseFilename=unwrapMedia-Setup
Compression=lzma2
SolidCompression=yes
SetupIconFile=..\..\app\icons\app.ico
UninstallDisplayIcon={app}\unwrapMedia.exe

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "..\..\app\build\compose\binaries\main\app\unwrapMedia\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs

[Icons]
Name: "{group}\unwrapMedia"; Filename: "{app}\unwrapMedia.exe"
Name: "{autodesktop}\unwrapMedia"; Filename: "{app}\unwrapMedia.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\unwrapMedia.exe"; Description: "{cm:LaunchProgram,unwrapMedia}"; Flags: nowait postinstall skipifsilent

[Code]
// Safely terminate previous running unwrapMedia app processes without killing the installer itself
procedure KillPreviousProcess();
var
  ResultCode: Integer;
  InstallerExe: String;
begin
  InstallerExe := ExtractFileName(ExpandConstant('{srcexe}'));
  // If the installer itself is named unwrapMedia.exe, we must NOT use naive taskkill /IM unwrapMedia.exe
  // because that would terminate the installer itself!
  if CompareText(InstallerExe, 'unwrapMedia.exe') = 0 then
  begin
    // Filter out the installer process by comparing file path in PowerShell
    Exec('powershell.exe', '-NoProfile -NonInteractive -Command "$inst = ''' + ExpandConstant('{srcexe}') + '''; Get-Process -Name unwrapMedia -ErrorAction SilentlyContinue | Where-Object { $_.Path -ne $inst } | Stop-Process -Force"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end
  else
  begin
    // Installer has a different name (unwrapMedia-Setup.exe), safe to kill unwrapMedia.exe
    Exec('taskkill.exe', '/F /T /IM unwrapMedia.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
end;

function InitializeSetup(): Boolean;
begin
  KillPreviousProcess();
  Sleep(500);
  Result := True;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
  begin
    KillPreviousProcess();
    Sleep(500);
  end;
end;
