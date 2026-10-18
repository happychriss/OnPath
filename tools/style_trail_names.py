"""Trail and route NAME labels for the OnPath style (e.g. "Algunder Waalweg").

Called by make_style.py: sources() is merged into the style sources, layers() is inserted
above trails/POIs and below place/peak labels. Owned separately from the colour scheme.
"""


def sources():
    return {}


def layers():
    return []
