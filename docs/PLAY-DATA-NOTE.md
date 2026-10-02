# 4Link — Data safety and privacy-policy changes needed before release (P4)

For the owner to approve. Nothing below has been applied to `play/DATA_SAFETY.md`,
`play/PRIVACY_POLICY.md` or the website; the website is never published without
the owner's explicit "publish".

## What is new, in data terms

1. With 4Link, 4Dictate can pass PART OF A DICTATION to another app ON THE SAME
   PHONE: the arguments the model filled for the function the user confirmed
   (for example the text of a note). Nothing leaves the phone through 4Link
   itself. The transcript still goes to the tidy-up provider the user chose, as
   today; the catalogues (function names and descriptions of installed 4Link
   apps) are added to that request.
2. Family apps (ours) receive this automatically. Any other app receives it
   only after the user approved it in "Apps 4Dictate may use" AND paired it on
   the receiving side AND confirmed the individual action.

## Privacy policy (play/PRIVACY_POLICY.md, mr-biz.uk/4dictate/privacy/)

3. Rewrite section 5 "Other apps on your phone": name 4Link; say that 4Dictate
   can ask apps by the same developer (4Zones, and others as they join) and
   apps you have approved to carry out what you said; that what is sent is
   the details of that one action, shown to you before it happens; that the
   approval screen shows what each function receives; that approvals can be
   removed at any time in Settings; that nothing about this leaves your phone;
   and that 4Dictate never operates another app's screen for this.
4. Section 3 "Tidy-up": add that, when 4Link apps are installed, the list of
   their functions (names and descriptions, not your data) is sent with the
   dictation to the tidy-up service so it can choose one.
5. "What 4Dictate keeps on your phone": add the approved-apps list, the call
   log (time, app, function, result; never the text) and the cached
   catalogues.

## Play Console Data safety (play/DATA_SAFETY.md)

6. Data types collected: unchanged (Voice or sound recordings; Other
   user-generated content). The dictated text passed to another app is the
   same "Other user-generated content" already declared.
7. "Shared": the current answer is No, on Google's exemption for transfers made
   on a user-initiated action with consent. A 4Link call to a NON-family app
   is user-initiated three times over (approval, pairing, per-action
   confirmation), so the answer can stay No — but Google's current wording on
   on-device transfers to other apps must be re-read before submitting, as the
   file already says for every answer. If it has to become Yes, the purpose is
   "App functionality", the type "Other user-generated content".
8. Family apps are the same developer, so no "third party" is involved there.

## Accessibility declaration (play/ACCESSIBILITY_DECLARATION.md, ACCESSIBILITY_SPEC.md)

9. No change: the accessibility service does nothing for 4Link. Worth one
   sentence in the declaration saying so, so a reviewer who sees "AI picks a
   function" does not assume the service carries it out (spec §2, P1–P2).

## Order

10. Owner approves this note → the three documents are edited together (the
    labels must match in substance) → Play Console answers updated → website
    copy published only on the owner's "publish" → then 4Link ships.
