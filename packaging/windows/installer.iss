[Setup]
AppId={{2D73CA07-DCD4-4B4E-B6A3-B9DA2BC41A9D}
AppName=unwrapMedia
AppVersion=1.5.1
DefaultDirName={userpf}\unwrapMedia
DefaultGroupName=unwrapMedia
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
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
