[Setup]
AppId={{2D73CA07-DCD4-4B4E-B6A3-B9DA2BC41A9D}
AppName=unwrapMedia
AppVersion=1.11.0
DefaultDirName={userpf}\unwrapMedia
DefaultGroupName=unwrapMedia
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
CloseApplications=force
RestartApplications=no
OutputDir=Output
OutputBaseFilename=unwrapMedia
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
// Safely terminate previous running unwrapMedia process and its child processes tree (/T)
procedure KillPreviousProcess();
var
  ResultCode: Integer;
begin
  // /F: Force kill, /T: Kill child process tree (ffmpeg/ffprobe spawned by unwrapMedia)
  // Targeting only unwrapMedia.exe ensures other third-party ffmpeg processes are never touched.
  Exec('taskkill.exe', '/F /T /IM unwrapMedia.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
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
