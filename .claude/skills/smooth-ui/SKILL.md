---
name: smooth-ui
description: How to build an app's screens so that nothing on them jumps and no frame is dropped - what comes and goes, animations, drawing, gestures, lists, transitions, and the work kept off the main thread. Use before writing or changing any screen, composable, animation, gesture or view model of a Compose app, and to review one.
---

# Screens that never jump and never drop a frame

The owner wants every app perfectly smooth. These rules come from what audits of the apps found; each one names
the mistake it prevents. Read them before you change a screen, and check your change against the list at the end.

This skill is app-machinery's: its monthly refresh copies it into every app. Change it there, not in an app.

## Nothing jumps

What appears, goes away or changes size while a screen is shown either keeps its place, so nothing around it moves,
or opens and gives back its room with an animation, so everything around it glides. Never a sudden reflow.

- **A line that is only information** (a status, a hint) and can keep its room: keep it laid out and fade it
  (`graphicsLayer { alpha }`), hidden from screen readers while invisible. A status that vanished when a song started
  made the whole screen below it jump up.
- **Something that comes and goes** (a card, a banner, a button, a warning): `AnimatedVisibility` inside the
  `Column` or `Row` (its scope gives it the right expand and shrink). While it closes, it must still show what it
  showed: keep the last non-null value (a `rememberLast`-style helper) instead of reading state that is already gone.
  A button that closes must stop acting (`enabled = false` or a no-op).
- **One thing turning into another of another size** (an instruction into a countdown, a loading line into content, a
  "no match" note into results, a status into an error): `AnimatedContent`.
- **A container whose content grows or shrinks** (a card that opens, a text that rewraps, a FlowRow whose chips
  change): `animateContentSize()`. It clips to its bounds: never on something with a halo, a glow or a dragged item
  that leaves its bounds.
- **The spacing of a `spacedBy` column or row arrives at once**: the gap before an item appears the moment it starts
  to enter and goes only when its exit ends, a step of the gap's size. Use a spacing that grows with the item (an
  arrangement whose gap before an item is in proportion to its height up to the gap), or put the item's space inside
  its animated content and none in the parent.
- **A sibling that takes the leftover room** (`weight(1f)`, `fillMaxHeight`): any step in the others' sizes moves or
  resizes it at once. Give the animated items their space inside their animation.
- **Lazy lists**: give items stable keys and `Modifier.animateItem()` so rows glide when they are reordered, filtered
  or removed; sections that come and go with filters need keys too (keys of different kinds must not collide). Don't
  animate placement while an item above is animating its size: the rows below trail behind and cover it. Put
  something that appears above a control the user just tapped (titles above filter chips) in the same item, opening
  its room, so the control doesn't jump away from the finger.
- **The first frame**: a screen shows its data from its first frame when the data is there (pass the current value as
  the initial one, never `null` then the value). Start loading a screen's data before its transition ends (at first
  composition or earlier), never on resume after it: a page that showed "Loading…" for its whole flight, then swapped
  its content in, looked broken.
- **Values that are known early are set early**: a list of locked options shown complete and then trimmed after a
  background count looked like a glitch. What is read in the background at start-up (a saved game, settings) and is
  there already when a screen starts is its initial state, not `false` then `true` a moment later: a Resume button that
  slid in after the home screen appeared looked broken.

## Every frame on time

- **Motion follows the frames, never a timer.** Animations, or a frame clock (`withFrameNanos`,
  `withInfiniteAnimationFrameMillis`) that reads the wall clock each frame. A progress ring driven by a 100 ms `delay`
  loop moved in uneven steps. Text that changes with time (a timer) updates on frame boundaries too
  (`derivedStateOf { ms / 100 }` of a frame clock).
- **Read what changes every frame while drawing or laying out, not while composing**: `graphicsLayer {}`,
  `drawBehind {}`, `Canvas`, `offset {}`, `Modifier.layout {}`, lambdas passed down instead of values. A screen that read
  a ticking time at its top recomposed entirely ten times a second. Use `derivedStateOf` for coarse facts derived from
  fast values ("the clip is still running").
- **What redraws every frame has a layer of its own** (`Canvas(Modifier.graphicsLayer())`, `graphicsLayer()` before
  `drawBehind`): otherwise it re-records the nearest layer, often a whole screen. A ring that read a frame clock in a
  layer-less Canvas redrew the entire game screen every frame. Particles, twinkles, foils, pulsing outlines: same.
- **Nothing is allocated per frame in draw code**: no new `Path`, `Brush`, shader, `Stroke`, outline, list or text
  layout in a draw lambda that runs every frame. Build them in `drawWithCache` or `remember`, reuse a `Path` with
  `rewind()`, draw one unit shape under `translate`/`rotate`/`scale`. Thousands of native objects a second meant
  garbage-collection pauses, worse on iOS.
- **Don't animate layout to animate looks**: an animated `size`, `padding` or `width` relays out (and re-measures text
  at a new size) every frame. Lay out at the final size and animate `graphicsLayer` scale or translation; animate only
  the room a slot takes, in `Modifier.layout {}`. Don't animate the same thing twice (a spring plus
  `animateContentSize`).
- **Cache what drawing builds per size**, and keep the size steady: a record whose diameter followed a parent's height
  animation threw away all its caches every frame. Lay it out at its settled size and scale it.
- **Keep animations continuous when their speed changes**: accumulate a phase (`phase += dt * speed`) instead of
  `sin(time * speed)`, or every star jumps when the speed changes.
- **Colours change with the motion they belong to**: `animateColorAsState`, or a pressed key that springs while its
  colour snaps looks cheap. Give colours the motion's stiffness without its bounce: a colour that overshoots flashes.
  A pulse ends by fading out, not vanishing.
- **Text drawn with a moving brush** (a foil, a shine): draw the laid-out text with a cached brush that you translate,
  not in an offscreen layer with `BlendMode.SrcAtop`: the layer clips the marks that reach past the text's box (Thai,
  Vietnamese, Hindi) and costs a pass.
- **Screen and phase transitions**: fade the old screen out quickly, then the new one in (`fadeOut(tween(90))`, then
  `fadeIn(tween(220, 90))`): two full screens at half opacity for a long spring cost and look muddy. Never swap a
  screen's content in the middle of a navigation transition with an early return; crossfade it, and let heavy first
  frames land after the transition.
- **Expensive things appear before they are needed**: compose a card's hidden face from the start (alpha 0 in its own
  layer) rather than on the frame it flips, when it turns fastest.

## Gestures follow the finger

- While a finger holds something, it is exactly under the finger: apply the drag in `offset {}` (or `snapTo` an
  `Animatable` each event), measured from the down position so the touch slop is included. Animate only on release,
  with the release velocity. A dragged card on a spring trailed the finger by 50 ms.
- Hit-testing and hover use one coordinate space: under a scaled parent (a screen shrunk to fit), convert with
  `localToRoot`, or the highlighted target runs ahead of the finger.
- Items swapped during a drag (reordering) glide to their new place; the displaced neighbour must not jump a row. A
  released item glides the rest of the way and stays on top until it lands; one grabbed while it glides is taken from
  where it is drawn.
- A tap that starts work ignores a second tap while the work runs, and drops the work's result if what it was for has
  changed meanwhile (the player left, the answer changed).

## The main thread only draws

Anything that takes more than a few milliseconds runs off the main thread (`withContext(Dispatchers.Default/IO)` or
the app's background dispatcher), and its result comes back as state:

- **System queries**: the package manager (installed apps, app icons, intent queries), `canOpenURL`, notification
  access, media libraries (`MPMediaQuery`, MediaStore). Never in composition, never per item: query once into state,
  refresh it on resume, and make checks pure functions of that state. Opening a list asked the system about installed
  apps for every song, hundreds of IPC calls in one frame.
- **Files and parsing**: JSON reads, a saved game, a big catalogue. Read once, keep what was read, never decode the
  same file twice; at start-up, defer heavy loads until the first screen has drawn and give them a lower priority.
- **Big collections**: searching, filtering, sorting, counting, dealing from a pool of thousands. A tap that dealt the
  next item on the main thread froze the button and lost the transition's first frames. Guard against a second tap
  while the work runs.
- **Recounts**: redo work only when its inputs changed (not for every settings tap or resume), one at a time
  (`limitedParallelism(1)`, so cancelled runs don't pile up), and keep the previous list or set instance when the new one
  is equal, so screens skip recomposing.
- **Formatters**: number and date formatters cached per locale and style, never built per call in composition.
- **Composition stays cheap**: pass each section only the fields it reads, so a keystroke doesn't recompose a whole
  form; `remember` objects of lambdas (action holders) instead of rebuilding them on every recomposition; give list
  items only stable or remembered arguments (data classes, `@Immutable` holders); don't compose hundreds of rows at
  once (make them lazy items). Collect flows with `collectAsStateWithLifecycle()`, never `.value` in composition.
- **One look at the system, shared**: what the phone has (installed apps, permissions) lives in one app-wide
  `StateFlow`, looked at again once when the app comes back in front (off the main thread where the platform allows
  it); screens and view models read it and never ask the system themselves. Two screens that each asked on resume did
  the same calls twice, on the main thread.
- **Start-up**: hold the heavy loads (catalogues, indexes) until the first screen has drawn its first frames, then run
  them at a low priority (on Android just above background priority, which itself can confine a thread to the slow
  cores; on iOS the utility queue), and decode each file once even when two screens ask for it at the same time.
- **Faster code gives the same results**: before speeding up an algorithm whose output users see (a shuffle, a
  ranking), record what it produces for a set of inputs in a test, and keep that test passing.

## Before you open the pull request

- [ ] Everything that appears, goes or changes size while shown keeps its place or animates its room, including the
      spacing around it and siblings that take the leftover room.
- [ ] Everything that moves follows the frames and is read while drawing or laying out; per-frame drawing has its own
      layer and allocates nothing.
- [ ] Dragged things stay exactly under the finger.
- [ ] No system query, file read, parse or big-list work on the main thread or in composition; data loads before the
      screen's transition ends.
- [ ] The first frame shows what the state already has.
- [ ] Where the app has a way to measure frames (a `run-app` skill with `gfxinfo`), measure the screen you changed
      before and after.
