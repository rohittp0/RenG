# A Geometry chooses its blend mode

A Geometry and a Sticker may choose `OVER`, `ADDITIVE`, `MULTIPLY` or `SCREEN`; `OVER` remains the
default. Let `Cs` be premultiplied source RGB, `As` source alpha, and `Cd` and `Ad` the destination.
The RGB results are `Cs + Cd(1-As)` for over, `Cs + Cd` for additive,
`Cs*Cd + Cd(1-As)` for multiply, and `Cs + Cd(1-Cs)` for screen. Every mode keeps the same coverage
equation, `As + Ad(1-As)`. These are implemented with separate RGB factors while alpha always uses
`ONE, ONE_MINUS_SRC_ALPHA`.

The renderer changes blend factors only when adjacent items choose different modes. The Geometry
pass and both Sticker passes restore `OVER` afterwards; the bloom emission redraw is separate and
keeps its own established blend function unchanged.

Both parameters are appended and default to `OVER`, preserving existing source calls and the
`componentN` positions of earlier data-class properties. Older serialized documents decode as
`OVER`. Canonical encoding omits Sticker tag 3 and Geometry tag 8 when all values they represent are
default, so existing default-only plans keep their identities; non-default enum values use stable
unsigned-16-bit wire values.
