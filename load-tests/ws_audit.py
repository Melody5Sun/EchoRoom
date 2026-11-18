"""Business-ready connection intervals shared by capacity verifiers."""


def continuous_windows(events, role, run, count):
    opens, windows = {}, {}
    for event in events:
        if event.get('run') != run or event.get('role') != role:
            continue
        vu = event['receiver']
        if event['audit'] == 'ready':
            if vu in opens or vu in windows:
                raise ValueError(f'{role} reconnected during the measured phase')
            opens[vu] = event['at']
        elif vu in opens:
            windows[vu] = (opens.pop(vu), event['at'])
    if opens or len(windows) != count:
        raise ValueError(f'{role}: expected {count} complete business-ready intervals, got {len(windows)}')
    return list(windows.values())
