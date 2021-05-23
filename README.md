# Tarantool Jepsen Test

This is a test suite, written using the [Jepsen distributed systems testing
library](https://jepsen.io), for
[Tarantool](https://github.com/tarantool/tarantool). It provides a single
workload, which uses [Elle](https://github.com/jepsen-io/elle) to find
transactional anomalies up to strict serializability.

We include a wide variety of faults, including network partitions, process
crashes, pauses, clock skew, and membership changes.

## Prerequisites

You'll need a Jepsen cluster running Ubuntu, which you can either [build
yourself](https://github.com/jepsen-io/jepsen#setting-up-a-jepsen-environment)
or run in
[AWS](https://aws.amazon.com/marketplace/pp/B01LZ7Y7U0?qid=1486758124485&sr=0-1&ref_=srh_res_product_title)
via Cloudformation.

The control node needs a JVM, Graphviz, and the [Leiningen
build system](https://github.com/technomancy/leiningen#installation). The first
two you can get (on Ubuntu) via:

```shell
sudo apt install openjdk8-jdk graphviz
```

Jepsen will automatically install dependencies (e.g. `git`, build tools,
various support libraries) on DB nodes, and clone and install Tarantool locally
on each DB node.

## Usage

To get started, try

```
lein run test --nodes-file nodes
```

To test a particular version of Tarantool, pass `--version`. It accepts two
kind of versions: branch version (for example 2.2) to use a latest version
of package from this branch and GIT commit hash to use version built on this
commit. You may also want to provide a custom `--time-limit` for each test. To
run several iterations of each test, use `--test-count`. A thorough test run
might look like:

```
lein run test --username root --nodes-file nodes --version 2.5 --time-limit 600 --test-count 10
lein run test --nodes-file node --engine vinyl --concurrency 20 --time-limit 100
```

To focus on a particular set of faults, use `--nemesis`

```
lein run test --nemesis partition,kill
```

To see all options, try

```
lein run test --help
```

## License

Copyright © 2020-2021 Mail.Ru Group Ltd

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
