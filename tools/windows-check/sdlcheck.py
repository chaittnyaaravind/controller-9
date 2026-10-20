"""How SDL (used by most indie and Steam games) sees the pad on Windows. Mirrors tools/linux-check/sdlcheck.c.
Usage: python sdlcheck.py <seconds>   (needs: pip install pysdl2==0.9.17 pysdl2-dll==2.32.10)"""
import ctypes, json, sys, time
import sdl2

def out(**kw):
    print(json.dumps(kw), flush=True)

secs = float(sys.argv[1]) if len(sys.argv) > 1 else 15
if sdl2.SDL_Init(sdl2.SDL_INIT_GAMECONTROLLER | sdl2.SDL_INIT_EVENTS) != 0:
    out(error=sdl2.SDL_GetError().decode()); sys.exit(1)
end = time.perf_counter() + secs; gc = None; e = sdl2.SDL_Event()
while time.perf_counter() < end:
    while sdl2.SDL_PollEvent(ctypes.byref(e)):
        t = e.type
        if t == sdl2.SDL_CONTROLLERDEVICEADDED and gc is None:
            gc = sdl2.SDL_GameControllerOpen(e.cdevice.which)
            out(sdl="%d.%d" % (sdl2.SDL_MAJOR_VERSION, sdl2.SDL_MINOR_VERSION),
                controller=sdl2.SDL_GameControllerName(gc).decode(), type=sdl2.SDL_GameControllerGetType(gc),
                rumble=bool(sdl2.SDL_GameControllerHasRumble(gc)))
        elif t in (sdl2.SDL_CONTROLLERBUTTONDOWN, sdl2.SDL_CONTROLLERBUTTONUP):
            out(t=e.cbutton.timestamp, button=sdl2.SDL_GameControllerGetStringForButton(e.cbutton.button).decode(),
                down=int(t == sdl2.SDL_CONTROLLERBUTTONDOWN))
        elif t == sdl2.SDL_CONTROLLERAXISMOTION and abs(e.caxis.value) > 30000:
            out(t=e.caxis.timestamp, axis=sdl2.SDL_GameControllerGetStringForAxis(e.caxis.axis).decode(), v=e.caxis.value)
        elif t == sdl2.SDL_CONTROLLERDEVICEREMOVED:
            out(removed=e.cdevice.timestamp)
    sdl2.SDL_Delay(2)
