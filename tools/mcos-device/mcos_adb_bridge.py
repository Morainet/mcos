#!/usr/bin/env python3
"""Serve the MCOS Agent's read-only device inspection tool over authorized ADB."""

import argparse
import os
import re
import sys
import time

from mcos_device import APPROVAL_TITLE, PACKAGE, SAMPLE_TITLE, Device, DeviceError, sample_text

MAILBOX = 'files/agent-adb'
REQUEST = re.compile(r'([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\.request')


def inspect_screen(device):
    nodes = device.observe()
    lines = sample_text(nodes).splitlines()
    labels = {node.get('content-desc') for node in nodes if node.get('package') == PACKAGE}
    if APPROVAL_TITLE in lines or any(re.fullmatch(r'Export inspection demo-[0-9]+\?', line) for line in lines):
        return 'approval_required'
    if SAMPLE_TITLE in lines:
        return 'record_workspace'
    composer = 'Describe a task' in lines or 'Describe a task' in labels
    agent_home = ('Task menu' in labels and composer) or (
        'MCOS' in lines and (composer or 'Task history' in labels)
    )
    task_library = 'Tasks' in lines and 'Back to workspace' in labels
    if agent_home or task_library:
        return 'agent_workspace'
    return 'outside_mcos'


def run_mailbox_command(device, script):
    return device.invoke('shell', f"run-as {PACKAGE} sh -c '{script}'")


def serve_requests(device):
    listing = run_mailbox_command(device, f'mkdir -p {MAILBOX} && touch {MAILBOX}/connected && ls {MAILBOX}')
    names = set(listing.splitlines())
    for name in sorted(names):
        match = REQUEST.fullmatch(name)
        if match is None:
            continue
        request_id = match.group(1)
        if f'{request_id}.response' in names:
            continue
        try:
            state = inspect_screen(device)
        except DeviceError:
            state = 'unavailable'
        request = f'{MAILBOX}/{name}'
        temporary = f'{MAILBOX}/{request_id}.tmp'
        response = f'{MAILBOX}/{request_id}.response'
        # Only UUIDs and a local enum change these fixed commands.
        run_mailbox_command(device, f'if [ -f {request} ]; then printf %s {state} > {temporary}'
                                   f' && mv {temporary} {response}; fi')
        print(f'ADB observation: {state}', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='ADB serial of the authorized MCOS debug device')
    parser.add_argument('--adb', default=os.environ.get('MCOS_ADB', 'adb'))
    args = parser.parse_args()
    device = Device(args.adb, args.serial)
    try:
        if {'serial': args.serial, 'state': 'device'} not in device.devices():
            raise DeviceError('The selected device is not connected or authorized.')
        print('Serving the MCOS Agent ADB inspection tool.', flush=True)
        while True:
            serve_requests(device)
            time.sleep(1)
    except KeyboardInterrupt:
        return 0
    except DeviceError as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
