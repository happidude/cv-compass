"""PDF preflight; imported only inside a supervised engine child."""
from pathlib import Path


def preflight(path: Path, max_pages: int) -> int:
    from pdfminer.pdfparser import PDFParser, PDFSyntaxError
    from pdfminer.pdfdocument import PDFDocument, PDFPasswordIncorrect
    from pdfminer.pdfpage import PDFPage
    try:
        with path.open('rb') as stream:
            doc = PDFDocument(PDFParser(stream), password='')
            # An empty user password can open an encrypted PDF. Opening alone is
            # not evidence that the document is unencrypted.
            if doc.encryption is not None:
                raise ValueError('FILE_ENCRYPTED')
            count = 0
            for _ in PDFPage.create_pages(doc):
                count += 1
                if count > max_pages:
                    raise ValueError('PAGE_COUNT_LIMIT')
            if not count:
                raise ValueError('FILE_CORRUPT')
            return count
    except PDFPasswordIncorrect:
        raise ValueError('FILE_ENCRYPTED') from None
    except (PDFSyntaxError, EOFError):
        raise ValueError('FILE_CORRUPT') from None
