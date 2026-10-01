/**
 * Experimental OMR mode based on the vision capability of the Claude Code session the user works
 * in.
 * <p>
 * Claude performs only the visual/semantic recognition of page images and writes a JSON score
 * description; Audiveris converts this description into MusicXML via ProxyMusic.
 * No Anthropic API key nor network access is used by Audiveris.
 * The regular Audiveris OMR pipeline is not affected by this package.
 *
 * @see org.audiveris.omr.claude.ClaudeOmr
 */
package org.audiveris.omr.claude;
