"""Short-lived signed URLs for MEDIA downloads (the built APKs).

`/media/` requires a login (see `config.views.protected_media`), and on its
own that would break the Download page's QR code — the entire point of that QR
is that you scan it *with the phone you're about to sideload onto*, and that
phone's browser has no whyfi session. Making the operator log into the PWA on
the phone first, just to fetch a file, is a worse answer than this.

So the URL in the QR (and in `/app/latest/`'s `download_url`) carries a token
signed with `SECRET_KEY` that grants exactly one MEDIA path for a short
window. Deliberately not a general-purpose capability system: it names a
single path, it expires, and it's only ever minted into a response that
already required authentication to fetch.
"""

from django.core import signing

MEDIA_TOKEN_SALT = "whyfi.media-download"

# Long enough to notice the QR, unlock the phone and scan it; short enough
# that a URL pasted into a chat log doesn't stay live. Only checked when the
# request starts, so a slow download over mobile data isn't cut off by it.
MEDIA_TOKEN_MAX_AGE_SECONDS = 30 * 60


def sign_media_path(name: str) -> str:
    """Token granting `name` (a storage-relative MEDIA path) for a while."""
    return signing.dumps(name, salt=MEDIA_TOKEN_SALT)


def media_token_grants(path: str, token: str) -> bool:
    if not token:
        return False
    try:
        granted_path = signing.loads(token, salt=MEDIA_TOKEN_SALT, max_age=MEDIA_TOKEN_MAX_AGE_SECONDS)
    except signing.BadSignature:
        # Covers SignatureExpired too (it's a subclass).
        return False
    # Path-scoped on purpose: a token minted for one release must not be
    # usable to fetch a different file out of MEDIA.
    return granted_path == path
