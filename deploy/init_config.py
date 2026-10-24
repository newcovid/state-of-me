"""Initialize a new multi-user instance with account-based device pairing."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
from urllib.parse import urlsplit


def configurations(url, groups=()):
    endpoint = url.rstrip('/')
    parsed = urlsplit(endpoint)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('Use an HTTPS service URL without credentials, query or fragment')
    if any(not re.fullmatch(r'\d{5,12}', group) for group in groups):
        raise ValueError('Each group must be a 5-12 digit QQ group number')
    server_groups, bot_groups = {}, {}
    for group in groups:
        token = secrets.token_urlsafe(48)
        server_groups[group] = {'token': token}
        bot_groups[group] = {'bot_token': token}
    server = {'ttl_seconds': 45,
              'accounts': {'registration': True, 'max_users': 100, 'origin': parsed.scheme+'://'+parsed.netloc, 'bot_groups': server_groups}}
    return server, {'url': endpoint, 'groups': bot_groups}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True, help='External HTTPS URL, including /presence if used')
    parser.add_argument('--group', action='append', default=[], help='Optional QQ group; repeat for more groups')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    try:
        server, bot = configurations(args.url, args.group)
    except ValueError as error:
        parser.error(str(error))
    args.output.mkdir(parents=True, exist_ok=True)
    targets = [(args.output/'config.json', server)]
    if args.group:
        targets.append((args.output/'astrbot-state-of-me.json', bot))
    if any(path.exists() for path, _ in targets):
        parser.error('Configuration already exists; refusing to overwrite. Use another output directory or edit existing settings.')
    for path, value in targets:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
        print('Created:', path)
    print('Registration is enabled; public sharing is off. Read docs/QUICKSTART.md for permissions and HTTPS proxy setup.')


if __name__ == '__main__':
    main()
