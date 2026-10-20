; NSIS hooks: install / remove the NineVhf driver alongside the app — only when the signed driver
; package (NineVhf.inf, NineVhf.sys, NineVhf.cat, nine-devnode.exe) is shipped in $INSTDIR\driver.
; Without it (preview builds) the companion installs alone and reports "no virtual controller" honestly.
!macro NSIS_HOOK_POSTINSTALL
  IfFileExists "$INSTDIR\driver\NineVhf.inf" 0 c9_no_driver
    DetailPrint "Installing Controller-9 virtual gamepad driver"
    nsExec::ExecToLog '"$SYSDIR\pnputil.exe" /add-driver "$INSTDIR\driver\NineVhf.inf" /install'
    ; Create the root-enumerated device the driver binds to (devcon-equivalent, shipped with the WDK tools).
    nsExec::ExecToLog '"$INSTDIR\driver\nine-devnode.exe" install Root\NineVhf'
    Goto c9_done
  c9_no_driver:
    DetailPrint "No signed driver in this build: games will not see a controller yet"
  c9_done:
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  IfFileExists "$INSTDIR\driver\NineVhf.inf" 0 c9_no_driver_rm
    DetailPrint "Removing Controller-9 virtual gamepad driver"
    nsExec::ExecToLog '"$INSTDIR\driver\nine-devnode.exe" remove Root\NineVhf'
    nsExec::ExecToLog '"$SYSDIR\pnputil.exe" /delete-driver NineVhf.inf /uninstall /force'
  c9_no_driver_rm:
!macroend
