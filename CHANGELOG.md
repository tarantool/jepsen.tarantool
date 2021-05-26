# Change Log

All notable changes to this project will be documented in this file. This
change log follows the conventions of
[keepachangelog.com](https://keepachangelog.com/).

## Unreleased

### Removed

- Remove counter-dec workload (#60).
- Remove dead code (#62).
- Remove kill and pause nemeses from a standard set.
- Remove extra variables.

### Fixed

- Fix broken nemeses (pause, kill, partition).
- Enable :primaries for partition, kill and pause nemeses (#59).
- Fix test bank with accounts in separate tables.
- Mark a singleton instance as a leader manually (#92).
- Enable synchronous mode for spaces in bank tests.

### Changed

- Declare Lua variables as local to make interpreter happy.
- Allow to test Tarantool version built from source code.
- Add new namespace for client and move all operations to it
- Rewrite client in a register workload (#31).
- Remove --single-mode option (#36).
- Support independent keys in a register workload (#39).
- Skip DB teardown when --leave-db-running option specified (#45).
- Ignore warnings 'too long WAL write' in logs.
- Send write operations to primary nodes only.
- Disable bank-multitable-lua in 'test-all' run
- Enable Raft consensus and primary discovery in workloads (#42).
- Make spaces used in tests synchronous (#51).
- Update Tarantool instance file.
- Replace magic number with variable 'minimal-concurrency'.
- Update command line to execute tests in the README.
- Print workloads and nemeses before testing.
- Simplify transfer operation in bank test.
- Disable bank tests that depends on interactive transactions support.
- Disable test counter-inc, it is blocked by bug in Tarantool (#84).
- Set DEBIAN_FRONTEND before running Tarantool installer (#73).
- Use tuple field names instead of indexes in Tarantool instance file.
- Replace boolean-to-str function with str.
- Replace Travis CI with GH Actions.
- Enable test counter-inc back (#84).
- Distribute initial balances uniformly in bank tests (#94).

### Added

- Add initial version of Jepsen tests.
- Add set test (#6).
- Add counter test (#3).
- Add command-line option to enable MVCC engine (#41).
- Add Tarantool error codes to crash patterns (#46).
- Add options to execute all tests at once.
- Add basic nemesis faults support.
- Add primary node discovery (#43, #17).
- Add bank workload (#67).
- Enable uberjar support and build JAR file in CI.
