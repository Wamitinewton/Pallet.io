# web

Pallet's web app. For now this folder holds only the static designs. The app itself will be scaffolded
here as its own independent project, like each service under `services/`.

## Designs

`design/` is plain HTML, CSS and a small script, with no build step. Open `design/index.html` in a browser
to see every screen, grouped by where it sits and listed with the backend endpoints it uses.

```
design/
├── index.html          # every screen, the endpoints behind it, and the design tokens
├── assets/
│   ├── pallet.css      # tokens (light and dark) and every component
│   ├── pallet.js       # app shell, icons, theme toggle, menus, modals, preview states
│   ├── pallet-mark.svg # the logo mark as a vector
│   └── favicon.svg
└── pages/              # one file per screen
```

Each screen has a bar in the bottom corner for going back to the index, switching the theme and, where
a screen has them, flipping between its states (wrong password, expired link, disconnected repo, and
so on).

Colors come from the logo: `#2665FC` for actions and `#3AD98B` only for connected or done. Change a
token in `pallet.css` and it changes on every screen.

The sample data is made up: an organization called Kilima Labs, viewed by its owner.
