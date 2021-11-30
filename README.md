# Tarantool Jepsen Test

[![Building](https://github.com/tarantool/jepsen.tarantool/actions/workflows/build.yaml/badge.svg)](https://github.com/tarantool/jepsen.tarantool/actions/workflows/build.yaml)

This is a test suite, written using the [Jepsen distributed systems testing
library](https://jepsen.io), for
[Tarantool](https://github.com/tarantool/tarantool). It provides a number of
workloads, which uses [Elle](https://github.com/jepsen-io/elle) and
[Knossos](https://github.com/jepsen-io/knossos) to find
transactional anomalies up to strict serializability.

We include a wide variety of faults, including network partitions, process
crashes, pauses, clock skew, and membership changes.

## How to use

### Prerequisites

You'll need a Jepsen cluster running Ubuntu, which you can either [build
yourself](https://github.com/jepsen-io/jepsen#setting-up-a-jepsen-environment)
or run in
[AWS](https://aws.amazon.com/marketplace/pp/B01LZ7Y7U0?qid=1486758124485&sr=0-1&ref_=srh_res_product_title)
via Cloudformation.

The control node needs:

- A JVM with version 1.8 or higher.
- JNA, so the JVM can talk to your SSH.
- (optional) Gnuplot, that helps Jepsen renders performance plots.
- (optional) Graphviz, that helps Jepsen renders transactional anomalies.

These dependencies you can get (on Ubuntu) via:

```shell
sudo apt install -y openjdk8-jdk graphviz gnuplot
```

Jepsen will install dependencies (e.g. `git`, build tools, various support
libraries) as well as Tarantool itself automatically on all DB nodes
participated in test.

### Usage

Tests distributed as a JAR file suitable for running with JVM. Release
archives with JAR file, shell script for running JAR file, CHANGELOG.md and
README.md are
[published](https://github.com/tarantool/jepsen.tarantool/releases) for every
release. Before start one can download archive for latest release and unpack
it.

To see all options and their default values, try

```sh
./run-jepsen test --help
```

To run test `register` with Tarantool 2.8 10 times during 600 seconds, try:

```sh
./run-jepsen test --username root --nodes-file nodes --workload register
                  --version 2.8 --time-limit 600 --test-count 10
```

To run test `set` with Tarantool built using source code in master branch
during 100 seconds with 20 threads, try:

```sh
./run-jepsen test --nodes-file node --engine vinyl --workload set
                  --concurrency 20 --time-limit 100
```

To focus on a particular set of faults, use `--nemesis`

```sh
./run-jepsen test --nemesis partition,kill
```

### Options

- `--concurrency` - how many workers should we run? Must be an integer,
  optionally followed by n (e.g. 3n) to multiply by the number of nodes.
- `--engine` - what Tarantool data engine should we use? Available values are
  `memtx` and `vinyl`. Learn more about DB engines in Tarantool documentation.
- `--leave-db-running` - leave the database running at the end of the test, so
  you can inspect it. Useful for debugging.
- `--logging-json` - use JSON structured output in the Jepsen log.
- `--mvcc` - enable MVCC engine, learn more about it in Tarantool
  [documentation](https://www.tarantool.io/en/doc/latest/book/box/atomic/#atomic-transactional-manager).
- `--nemesis` - a comma-separated list of nemesis faults or groups of faults to
  enable. Nemeses groups are: `none` with none nemeses, `standard` includes
  `partition` and `clock`, `all` includes all nemeses listed below. Available
  nemeses are:
	- `clock` generates a nemesis which manipulates clocks.
	- `pause` pauses and resumes a DB's processes using `SIGSTOP` and `SIGCONT` signals.
	- `kill` kills a DB's processes using `SIGKILL` signal.
	- `partition` splits network connectivity for nodes in a cluster and then recover it.
- `--nemesis-interval` - how long to wait between nemesis faults.
- `--node` - node(s) to run test on. Flag may be submitted many times, with one
  node per flag.
- `--nodes` - comma-separated list of node hostnames.
- `--nodes-file` - file containing node hostnames, one per line.
- `--username` - username for login to remote server via SSH.
- `--password` - password for sudo access on remote server.
- `--strict-host-key-checking` - whether to check host keys.
- `--ssh-private-key` - path to an SSH identity file.
- `--test-count` - how many times should we repeat a test?
- `--time-limit` - excluding setup and teardown, how long should a test run
  for, in seconds?
- `--version` - what Tarantool version should we test? Option accepts two kind
  of versions: branch version (for example 2.2) to use a latest version of
  package from this branch or GIT commit hash to use version built on this
  commit.
- `--workload` - test workload to run. Available workloads are:
    - `bank` simulates transfers between bank accounts. Uses SQL to access to
      bank accounts.
    - `bank-multitable` simulates transfers between bank accounts when each
      account is in a separate space (table). Uses SQL to access to bank accounts.
    - `bank-lua` simulates transfers between bank accounts. Uses Lua functions
      to access to bank accounts.
    - `bank-multitable-lua` simulates transfers between bank accounts when each
      account is in a separate space (table). Uses Lua functions to access to
      bank accounts.
    - `counter-inc` increments a counter.
    - `register` models a register with read, write and CAS (Compare-And-Set)
      operations.
    - `set` inserts a series of unique numbers as separate instances, one per
      transaction, and attempts to read them back through an index.

## How to build

For building Jepsen tests locally one need to setup [Leiningen build
system](https://github.com/technomancy/leiningen#installation) and
[Clojure](https://clojure.org/guides/getting_started).

For building tests, try:

```sh
lein deps
lein compile
```

For running tests, try:

```sh
lein run test --nodes-file nodes --workload register --version 2.8 --time-limit 100
```

## License

Copyright © 2020-2021 VK Company Limited

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
https://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
