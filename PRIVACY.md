# Privacy policy

Effective 16 September 2026.

Aftergleam is a reader for research preprints. It has no account, no server of its own, no
analytics, no crash reporting, no advertising and no third-party tracking code. The developer
receives nothing about you or your use of the app.

## What stays on your device

Everything the app knows about you is stored only on your phone:

- the subjects you follow and your settings
- your reactions to papers, what you opened, saved and downloaded
- the reading model, which is trained on your phone from those reactions
- downloaded PDFs
- any library you import

Uninstalling the app, or clearing its data, deletes all of it.

## What the app sends, and to whom

To show you papers, the app has to ask the servers that publish them. Like any request over the
internet, these reveal your IP address to the server, along with a note that the request comes
from Aftergleam. The app only contacts the servers your subjects need.

| Server | What it receives |
|---|---|
| **arXiv** (arxiv.org) | the arXiv categories you follow, and a few related ones for suggestions outside your usual reading; your search terms; the titles in a library you import, to match them; the identifiers of papers from your past digests, three to twelve months later, to check whether they have since been published; and the identifier of any paper whose PDF you open |
| **bioRxiv and medRxiv** (biorxiv.org, medrxiv.org) | a range of dates, and the identifier of any paper whose PDF you open. Your subjects are not sent: papers are filtered on your phone |
| **OSF**, which hosts PsyArXiv, SocArXiv, EdArXiv and Law Archive (osf.io) | the name of the server and a date, and the identifier of any paper whose PDF you open. Your subjects are not sent. OSF stores its files with Google Cloud, so a PDF download is completed by storage.googleapis.com, which also receives that request |
| **Crossref** (crossref.org), for ChemRxiv, and for searching bioRxiv, medRxiv, ChemRxiv, PsyArXiv, SocArXiv, EdArXiv and Law Archive | a date and the app's contact address; and your search terms when you search online |
| **Hugging Face** (huggingface.co) | a request for its public list of the day's popular papers, which is the same for everyone. Nothing about you |

Search terms, keywords and imported titles are the most revealing of these, because you wrote
them or chose them. Imported titles go only to arXiv. Search terms go to arXiv and to Crossref,
and only when you search online; the On device and My library searches send nothing. Keywords,
if you add any, go to arXiv and to Crossref with each fetch, to ask for new papers that mention
them.

Each of these services has its own privacy policy, which applies to the requests it receives.

## When something leaves your device because you asked

- **Opening a paper's page** opens it in your browser, where the publisher's own terms apply.
  ChemRxiv papers are read this way, because ChemRxiv does not let apps download them.
- **Sharing** a paper or a PDF sends it to the app you choose.
- **Exporting a backup** writes a file where you choose. It contains your subjects,
  reactions and settings, so treat it as private.
- **Opening a file with another app** hands that one file to the app you choose.
- **Supporting the app** opens Ko-fi in your browser, or in a copy from Google Play the app's
  Play listing, where their own terms apply. The app sends them nothing. Whether and when the
  app shows its occasional note about this is worked out on your device from how many days
  you have read, and that count goes nowhere.
- **Writing to the developer** opens your mail app with the address filled in. Nothing is
  sent until you send it.

## Android backup

When you set up a new phone and copy your apps across directly, Android can bring your reading
history and settings with it. Aftergleam never allows them to be copied into a cloud backup
account. On Android 8, where the system cannot tell the two apart, nothing is backed up at all;
use the app's own export instead.

## Permissions

- **Internet** and **network state**: to fetch papers, and to avoid trying while offline.
- **Notifications**: optional, for the daily digest and the reading reminder. You can decline.

## Children

The app does not collect personal information from anyone, including children.

## Changes

Any change to this policy will be made in this file, in the app's public repository, with the
date above updated. The history of every change is visible there.

## Contact

Questions about privacy: hello@aftergleam.app, or open an issue at
https://github.com/jakobkreft/aftergleam/issues.
