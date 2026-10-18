/*
 * NineVhf — KMDF HID source driver built on the Microsoft Virtual HID Framework (VHF).
 *
 * One root-enumerated device (hardware id Root\NineVhf) exposes GUID_DEVINTERFACE_NINEVHF.
 * The 9-controller host opens it and creates up to four virtual HID gamepads (one VHF child
 * each). Reports are produced by the host (hosts/core/src/hid.rs) and passed through verbatim.
 * Every pad owned by a file handle is deleted when that handle closes, so a crashed host can
 * never leave a controller stuck with buttons held.
 *
 * Build: Visual Studio 2022 + WDK (see hosts/windows/README.md). Requires vhfkm.lib, Vhf.sys as
 * lower filter (INF). STATUS: source complete, NOT YET BUILT OR RUN — no Windows machine yet.
 */
#include <ntddk.h>
#include <wdf.h>
#include <initguid.h>
#include <vhf.h>
#include "public.h"
#include "descriptor.h"

typedef struct _PAD {
    VHFHANDLE  Vhf;
    WDFFILEOBJECT Owner;
    BOOLEAN    Active;
} PAD;

typedef struct _DEVICE_CONTEXT {
    WDFWAITLOCK Lock;
    PAD Pads[NINEVHF_MAX_PADS];
} DEVICE_CONTEXT, *PDEVICE_CONTEXT;
WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(DEVICE_CONTEXT, DeviceGetContext)

DRIVER_INITIALIZE DriverEntry;
EVT_WDF_DRIVER_DEVICE_ADD NineEvtDeviceAdd;
EVT_WDF_IO_QUEUE_IO_DEVICE_CONTROL NineEvtIoDeviceControl;
EVT_WDF_FILE_CLEANUP NineEvtFileCleanup;
EVT_WDF_OBJECT_CONTEXT_CLEANUP NineEvtDeviceCleanup;

static VOID NineDeletePad(PDEVICE_CONTEXT ctx, ULONG slot)
{
    /* Caller holds ctx->Lock, at PASSIVE_LEVEL. */
    if (ctx->Pads[slot].Active) {
        UCHAR neutral[NINEVHF_REPORT_LEN] = { 0 };
        HID_XFER_PACKET p;
        neutral[2] = 8; /* hat: null */
        p.reportBuffer = neutral;
        p.reportBufferLen = sizeof(neutral);
        p.reportId = 0;
        (VOID)VhfReadReportSubmit(ctx->Pads[slot].Vhf, &p);
        VhfDelete(ctx->Pads[slot].Vhf, TRUE);
        ctx->Pads[slot].Vhf = WDF_NO_HANDLE;
        ctx->Pads[slot].Owner = NULL;
        ctx->Pads[slot].Active = FALSE;
    }
}

NTSTATUS DriverEntry(_In_ PDRIVER_OBJECT DriverObject, _In_ PUNICODE_STRING RegistryPath)
{
    WDF_DRIVER_CONFIG config;
    WDF_DRIVER_CONFIG_INIT(&config, NineEvtDeviceAdd);
    return WdfDriverCreate(DriverObject, RegistryPath, WDF_NO_OBJECT_ATTRIBUTES, &config, WDF_NO_HANDLE);
}

NTSTATUS NineEvtDeviceAdd(_In_ WDFDRIVER Driver, _Inout_ PWDFDEVICE_INIT DeviceInit)
{
    NTSTATUS status;
    WDFDEVICE device;
    WDF_OBJECT_ATTRIBUTES attrs;
    WDF_FILEOBJECT_CONFIG fileConfig;
    WDF_IO_QUEUE_CONFIG queueConfig;
    PDEVICE_CONTEXT ctx;
    UNREFERENCED_PARAMETER(Driver);
    PAGED_CODE();

    WDF_FILEOBJECT_CONFIG_INIT(&fileConfig, WDF_NO_EVENT_CALLBACK, WDF_NO_EVENT_CALLBACK, NineEvtFileCleanup);
    WdfDeviceInitSetFileObjectConfig(DeviceInit, &fileConfig, WDF_NO_OBJECT_ATTRIBUTES);

    WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&attrs, DEVICE_CONTEXT);
    attrs.EvtCleanupCallback = NineEvtDeviceCleanup;
    status = WdfDeviceCreate(&DeviceInit, &attrs, &device);
    if (!NT_SUCCESS(status)) return status;

    ctx = DeviceGetContext(device);
    RtlZeroMemory(ctx->Pads, sizeof(ctx->Pads));
    status = WdfWaitLockCreate(WDF_NO_OBJECT_ATTRIBUTES, &ctx->Lock);
    if (!NT_SUCCESS(status)) return status;

    /* Sequential queue: IOCTLs run at PASSIVE_LEVEL, one at a time. */
    WDF_IO_QUEUE_CONFIG_INIT_DEFAULT_QUEUE(&queueConfig, WdfIoQueueDispatchSequential);
    queueConfig.EvtIoDeviceControl = NineEvtIoDeviceControl;
    WDF_OBJECT_ATTRIBUTES_INIT(&attrs);
    attrs.ExecutionLevel = WdfExecutionLevelPassive;
    status = WdfIoQueueCreate(device, &queueConfig, &attrs, WDF_NO_HANDLE);
    if (!NT_SUCCESS(status)) return status;

    return WdfDeviceCreateDeviceInterface(device, &GUID_DEVINTERFACE_NINEVHF, NULL);
}

static NTSTATUS NineCreatePad(WDFDEVICE device, PDEVICE_CONTEXT ctx, ULONG slot, WDFFILEOBJECT owner)
{
    VHF_CONFIG cfg;
    NTSTATUS status;
    if (ctx->Pads[slot].Active) {
        return ctx->Pads[slot].Owner == owner ? STATUS_SUCCESS : STATUS_DEVICE_BUSY;
    }
    VHF_CONFIG_INIT(&cfg, WdfDeviceWdmGetDeviceObject(device), sizeof(NineGamepadReportDescriptor),
                    (PUCHAR)NineGamepadReportDescriptor);
    cfg.VendorID = 0x1209;                       /* pid.codes */
    cfg.ProductID = (USHORT)(0x9C01 + slot);     /* placeholder until allocated, see docs/RELEASE.md */
    cfg.VersionNumber = 0x0100;
    status = VhfCreate(&cfg, &ctx->Pads[slot].Vhf);
    if (!NT_SUCCESS(status)) return status;
    status = VhfStart(ctx->Pads[slot].Vhf);
    if (!NT_SUCCESS(status)) {
        VhfDelete(ctx->Pads[slot].Vhf, TRUE);
        ctx->Pads[slot].Vhf = WDF_NO_HANDLE;
        return status;
    }
    ctx->Pads[slot].Owner = owner;
    ctx->Pads[slot].Active = TRUE;
    return STATUS_SUCCESS;
}

VOID NineEvtIoDeviceControl(_In_ WDFQUEUE Queue, _In_ WDFREQUEST Request, _In_ size_t OutLen,
                            _In_ size_t InLen, _In_ ULONG IoControlCode)
{
    WDFDEVICE device = WdfIoQueueGetDevice(Queue);
    PDEVICE_CONTEXT ctx = DeviceGetContext(device);
    WDFFILEOBJECT owner = WdfRequestGetFileObject(Request);
    NTSTATUS status = STATUS_INVALID_DEVICE_REQUEST;
    ULONG_PTR info = 0;
    PVOID inBuf = NULL;
    UNREFERENCED_PARAMETER(InLen);

    WdfWaitLockAcquire(ctx->Lock, NULL);
    switch (IoControlCode) {
    case IOCTL_NINEVHF_VERSION: {
        PULONG out;
        status = WdfRequestRetrieveOutputBuffer(Request, sizeof(ULONG), (PVOID*)&out, NULL);
        if (NT_SUCCESS(status)) { *out = NINEVHF_INTERFACE_VERSION; info = sizeof(ULONG); }
        break;
    }
    case IOCTL_NINEVHF_CREATE:
    case IOCTL_NINEVHF_DESTROY: {
        NINEVHF_SLOT* in;
        status = WdfRequestRetrieveInputBuffer(Request, sizeof(NINEVHF_SLOT), &inBuf, NULL);
        if (!NT_SUCCESS(status)) break;
        in = (NINEVHF_SLOT*)inBuf;
        if (in->Slot >= NINEVHF_MAX_PADS) { status = STATUS_INVALID_PARAMETER; break; }
        if (IoControlCode == IOCTL_NINEVHF_CREATE) {
            status = NineCreatePad(device, ctx, in->Slot, owner);
        } else if (ctx->Pads[in->Slot].Active && ctx->Pads[in->Slot].Owner != owner) {
            status = STATUS_ACCESS_DENIED;
        } else {
            NineDeletePad(ctx, in->Slot);
            status = STATUS_SUCCESS;
        }
        break;
    }
    case IOCTL_NINEVHF_SUBMIT: {
        NINEVHF_SUBMIT* in;
        HID_XFER_PACKET p;
        status = WdfRequestRetrieveInputBuffer(Request, sizeof(NINEVHF_SUBMIT), &inBuf, NULL);
        if (!NT_SUCCESS(status)) break;
        in = (NINEVHF_SUBMIT*)inBuf;
        if (in->Slot >= NINEVHF_MAX_PADS || !ctx->Pads[in->Slot].Active) { status = STATUS_INVALID_PARAMETER; break; }
        if (ctx->Pads[in->Slot].Owner != owner) { status = STATUS_ACCESS_DENIED; break; }
        p.reportBuffer = in->Report;               /* VHF copies the buffer before returning */
        p.reportBufferLen = NINEVHF_REPORT_LEN;
        p.reportId = 0;
        status = VhfReadReportSubmit(ctx->Pads[in->Slot].Vhf, &p);
        break;
    }
    default:
        break;
    }
    WdfWaitLockRelease(ctx->Lock);
    UNREFERENCED_PARAMETER(OutLen);
    WdfRequestCompleteWithInformation(Request, status, info);
}

VOID NineEvtFileCleanup(_In_ WDFFILEOBJECT FileObject)
{
    /* Host closed its handle (exit or crash): remove every pad it owned. */
    WDFDEVICE device = WdfFileObjectGetDevice(FileObject);
    PDEVICE_CONTEXT ctx = DeviceGetContext(device);
    ULONG i;
    WdfWaitLockAcquire(ctx->Lock, NULL);
    for (i = 0; i < NINEVHF_MAX_PADS; i++) {
        if (ctx->Pads[i].Active && ctx->Pads[i].Owner == FileObject) NineDeletePad(ctx, i);
    }
    WdfWaitLockRelease(ctx->Lock);
}

VOID NineEvtDeviceCleanup(_In_ WDFOBJECT Device)
{
    PDEVICE_CONTEXT ctx = DeviceGetContext((WDFDEVICE)Device);
    ULONG i;
    for (i = 0; i < NINEVHF_MAX_PADS; i++) NineDeletePad(ctx, i);
}
