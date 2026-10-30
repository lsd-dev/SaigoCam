/*
 * SaigoCam receiver - tiny bitmap-font text overlay (no font library needed).
 * Part of SaigoCam, licensed under the GNU General Public License v3; see ../../LICENSE.
 */
#ifndef SAIGOCAM_OVERLAY_H
#define SAIGOCAM_OVERLAY_H

#include <SDL.h>

/* Draws the lines on a translucent panel with its top-left corner at (x, y).
 * Text is shown in upper case; characters without a glyph are drawn blank.
 * scale is the size of one font pixel in screen pixels. */
void overlay_draw(SDL_Renderer *ren, int x, int y, int scale,
                  const char *const *lines, int n_lines);

#endif
