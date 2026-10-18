// Phase 0 feasibility probe (macOS): can an unentitled process create an
// OS-visible virtual HID gamepad with IOHIDUserDevice?
// Build: clang -framework IOKit -framework CoreFoundation vhid_probe.c -o vhid_probe
#include <CoreFoundation/CoreFoundation.h>
#include <IOKit/hid/IOHIDManager.h>
#include <IOKit/hidsystem/IOHIDUserDevice.h>
#include <mach/mach_time.h>
#include <stdio.h>

// Generic gamepad: 16 buttons, 4 x 16-bit axes.
static const uint8_t kDescriptor[] = {
    0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,             // Usage Page GD, Usage Game Pad, Collection App
    0x05, 0x09, 0x19, 0x01, 0x29, 0x10,             // Buttons 1..16
    0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02,
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, // X Y Z Rz
    0x16, 0x00, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x04, 0x81, 0x02,
    0xC0,
};

static CFNumberRef num(int v) { return CFNumberCreate(NULL, kCFNumberIntType, &v); }

int main(void) {
    CFMutableDictionaryRef props = CFDictionaryCreateMutable(
        NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFDataRef desc = CFDataCreate(NULL, kDescriptor, sizeof kDescriptor);
    CFDictionarySetValue(props, CFSTR(kIOHIDReportDescriptorKey), desc);
    CFDictionarySetValue(props, CFSTR(kIOHIDVendorIDKey), num(0x1209));   // pid.codes test VID
    CFDictionarySetValue(props, CFSTR(kIOHIDProductIDKey), num(0x0009));
    CFDictionarySetValue(props, CFSTR(kIOHIDProductKey), CFSTR("Controller-9 probe"));

    IOHIDUserDeviceRef dev = IOHIDUserDeviceCreateWithProperties(kCFAllocatorDefault, props, 0);
    if (!dev) {
        printf("RESULT: IOHIDUserDeviceCreateWithProperties returned NULL (no device created)\n");
        return 2;
    }
    printf("RESULT: virtual device object created\n");

    uint8_t report[10] = {0x01, 0x00, 0x00, 0x40}; // button 1 down, X slightly right
    IOReturn r = IOHIDUserDeviceHandleReportWithTimeStamp(dev, mach_absolute_time(), report, sizeof report);
    printf("HandleReport -> 0x%08x\n", r);

    // Is it visible to an ordinary HID client?
    IOHIDManagerRef mgr = IOHIDManagerCreate(NULL, 0);
    CFMutableDictionaryRef match = CFDictionaryCreateMutable(
        NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFDictionarySetValue(match, CFSTR(kIOHIDVendorIDKey), num(0x1209));
    IOHIDManagerSetDeviceMatching(mgr, match);
    IOHIDManagerOpen(mgr, 0);
    CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.5, false);
    CFSetRef found = IOHIDManagerCopyDevices(mgr);
    printf("Visible to IOHIDManager: %ld device(s)\n", found ? CFSetGetCount(found) : 0L);
    CFRelease(dev);
    return 0;
}
