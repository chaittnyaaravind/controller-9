; NSIS hooks: install / remove the NineVhf driver alongside the app.
; Expects the signed driver package (NineVhf.inf, NineVhf.sys, NineVhf.cat) in $INSTDIR\driver.
!macro NSIS_HOOK_POSTINSTALL
  DetailPrint "Installing Controller-9 virtual gamepad driver"
  nsExec::ExecToLog '"$SYSDIR\pnputil.exe" /add-driver "$INSTDIR\driver\NineVhf.inf" /install'
  ; Create the root-enumerated device the driver binds to (devcon-equivalent, shipped with the WDK tools).
  nsExec::ExecToLog '"$INSTDIR\driver\nine-devnode.exe" install Root\NineVhf'
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  DetailPrint "Removing Controller-9 virtual gamepad driver"
  nsExec::ExecToLog '"$INSTDIR\driver\nine-devnode.exe" remove Root\NineVhf'
  nsExec::ExecToLog '"$SYSDIR\pnputil.exe" /delete-driver NineVhf.inf /uninstall /force'
!macroend
