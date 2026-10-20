/* How SDL (the layer most Linux games and Steam use) sees the pad: name, GUID, mapping, events. */
#include <SDL2/SDL.h>
#include <stdio.h>
int main(int argc, char **argv) {
  double secs = argc > 1 ? atof(argv[1]) : 15;
  if (SDL_Init(SDL_INIT_GAMECONTROLLER | SDL_INIT_EVENTS) != 0) { printf("{\"error\":\"%s\"}\n", SDL_GetError()); return 1; }
  Uint32 end = SDL_GetTicks() + (Uint32)(secs * 1000); SDL_GameController *gc = NULL; SDL_Event e;
  while (SDL_GetTicks() < end) {
    while (SDL_PollEvent(&e)) {
      if (e.type == SDL_CONTROLLERDEVICEADDED && !gc) {
        gc = SDL_GameControllerOpen(e.cdevice.which);
        char guid[64]; SDL_JoystickGetGUIDString(SDL_JoystickGetDeviceGUID(e.cdevice.which), guid, sizeof guid);
        char *map = SDL_GameControllerMapping(gc);
        printf("{\"sdl\":\"%d.%d\",\"controller\":\"%s\",\"type\":%d,\"guid\":\"%s\",\"rumble\":%s,\"mapping\":\"%s\"}\n",
          SDL_MAJOR_VERSION, SDL_MINOR_VERSION, SDL_GameControllerName(gc), SDL_GameControllerGetType(gc), guid,
          SDL_GameControllerHasRumble(gc) ? "true" : "false", map ? map : "none");
        SDL_free(map); fflush(stdout);
      } else if (e.type == SDL_JOYDEVICEADDED && !SDL_IsGameController(e.jdevice.which)) {
        printf("{\"joystick_only\":\"%s\"}\n", SDL_JoystickNameForIndex(e.jdevice.which)); fflush(stdout);
      } else if (e.type == SDL_CONTROLLERBUTTONDOWN || e.type == SDL_CONTROLLERBUTTONUP) {
        printf("{\"t\":%u,\"button\":\"%s\",\"down\":%d}\n", e.cbutton.timestamp, SDL_GameControllerGetStringForButton(e.cbutton.button), e.type == SDL_CONTROLLERBUTTONDOWN); fflush(stdout);
      } else if (e.type == SDL_CONTROLLERAXISMOTION && (e.caxis.value > 30000 || e.caxis.value < -30000)) {
        printf("{\"t\":%u,\"axis\":\"%s\",\"v\":%d}\n", e.caxis.timestamp, SDL_GameControllerGetStringForAxis(e.caxis.axis), e.caxis.value); fflush(stdout);
      } else if (e.type == SDL_CONTROLLERDEVICEREMOVED) { printf("{\"removed\":%u}\n", e.cdevice.timestamp); fflush(stdout); }
    }
    SDL_Delay(2);
  }
  return 0;
}
