# Disclaimer — no warranty, no liability

**Use this software entirely at your own risk.**

This project is provided **"as is"**, without warranty of any kind, express or implied,
including but not limited to the warranties of merchantability, fitness for a particular
purpose and non-infringement. In no event shall the authors or contributors be liable for
any claim, damages or other liability, whether in an action of contract, tort or
otherwise, arising from, out of or in connection with the software or its use.

## What that means concretely

- The app runs on a **vehicle**. Installing third-party software on a car is your
  decision and your responsibility, including any effect on the head unit's stability,
  your warranty, your insurance, or your vehicle's roadworthiness.
- **Do not operate the app while driving.** Configure it parked. Nothing in it should
  require attention on the move, and looking at a screen instead of the road is on you.
- Telemetry may be **wrong, delayed or missing**. Never rely on it for range or charging
  decisions where being wrong matters — check the car's own instruments.
- The **unstable channel is untested on vehicles** by definition. It builds automatically
  from `main`; its OTA trigger is suspended during the suite safety and legal audit.
- Vehicle data is uploaded to **A Better Route Planner**, a third-party service governed
  by its own terms and privacy policy. This project has no control over what happens to
  data once sent.
- Reading vehicle properties uses **undocumented runtime interfaces**. They can change or
  disappear with any firmware update.
- Optional WinClose automation sends window-closing commands. Sensorless Comfort windows
  cannot confirm completion; command timing and motor/anti-pinch behavior have not been
  verified on this vehicle. Check physical operation while parked before relying on it.

## Not affiliated

This project is **not affiliated with, endorsed by, or supported by** SAIC Motor, MG
Motor, Iternio (A Better Route Planner), or Google. MG, MG4 and related names and logos are
trademarks of their respective owners. They are used solely to identify compatibility with
certain vehicles; no official origin, certification or approval is claimed.

## Contributors

Contributors provide their work on the same terms: no warranty, and no liability for how
anyone uses it.
