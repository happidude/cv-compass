"""Offline, evidence-backed CV extraction core."""

from .ocr_input import Document, Page, load_document, parse_document
from .pipeline import extract

__all__ = ['Document', 'Page', 'extract', 'load_document', 'parse_document']
