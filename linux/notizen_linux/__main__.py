"""`python -m notizen_linux` startet die App; `python -m notizen_linux sync` gleicht einmal ohne Oberfläche ab."""

import sys


def cli_sync() -> int:
    from . import config
    from .sync import AuthError, Client, ServerError, Syncer

    cfg = config.load()
    if not cfg.ready:
        print('Noch nicht eingerichtet: erst die App starten und verbinden.', file=sys.stderr)
        return 2
    try:
        rep = Syncer(cfg.root, Client(cfg.server, cfg.token), config.STATE_FILE).sync()
    except AuthError:
        print('Token ungültig.', file=sys.stderr)
        return 1
    except ServerError as e:
        print(e, file=sys.stderr)
        return 1
    print(f'{rep.pulled} geholt, {rep.pushed} gesendet, {rep.deleted} gelöscht, {len(rep.conflicts)} Konflikte')
    for c in rep.conflicts:
        print(f'  Konfliktkopie: {c}')
    for e in rep.errors:
        print(f'  Fehler: {e}', file=sys.stderr)
    return 0


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == 'sync':
        sys.exit(cli_sync())
    from .app import main

    sys.exit(main())
