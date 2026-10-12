#ifndef AppVersion
  #error AppVersion must be supplied by the build script.
#endif
#ifndef SourceDir
  #error SourceDir must be supplied by the build script.
#endif
#ifndef OutputDir
  #error OutputDir must be supplied by the build script.
#endif

[Setup]
AppId={{2D81C025-A13F-4F7C-B21B-4AC90915D7F5}
AppName=LocalDrop
AppVersion={#AppVersion}
AppPublisher=LocalDrop contributors
AppPublisherURL=https://github.com/bevyss/LocalDrop
AppSupportURL=https://github.com/bevyss/LocalDrop/issues
DefaultDirName={localappdata}\Programs\LocalDrop
DefaultGroupName=LocalDrop
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputDir={#OutputDir}
OutputBaseFilename=LocalDrop-Setup-{#AppVersion}-win-x64
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\LocalDrop.Windows.exe
CloseApplications=yes

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "*.pdb"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Icons]
Name: "{autoprograms}\LocalDrop"; Filename: "{app}\LocalDrop.Windows.exe"
Name: "{autodesktop}\LocalDrop"; Filename: "{app}\LocalDrop.Windows.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\LocalDrop.Windows.exe"; Description: "{cm:LaunchProgram,LocalDrop}"; Flags: nowait postinstall skipifsilent
