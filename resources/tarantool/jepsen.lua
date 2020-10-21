local single_mode = %TARANTOOL_SINGLE_MODE%

if single_mode then
  box.cfg {
    listen = '%TARANTOOL_IP_ADDRESS%:3301';
    log_level = 6;
    log_nonblock = false;
    too_long_threshold = 0.5;
    custom_proc_title = 'jepsen';
    memtx_memory = 1024*1024*1024;
    memtx_use_mvcc_engine = %TARANTOOL_MVCC%;
  }
else
  box.cfg {
    listen = '%TARANTOOL_IP_ADDRESS%:3301';
    replication = { %TARANTOOL_REPLICATION% };
    replication_synchro_quorum = %TARANTOOL_QUORUM%;
    replication_synchro_timeout = 0.2;
    replication_timeout = 1;
    election_mode = 'candidate';
    election_timeout = 0.5;
    log_level = 6;
    log_nonblock = false;
    too_long_threshold = 0.5;
    custom_proc_title = 'jepsen';
    memtx_memory = 1024*1024*1024;
    memtx_use_mvcc_engine = %TARANTOOL_MVCC%;
  }
end

local function bootstrap()
    box.schema.user.create('jepsen', {password = 'jepsen'})
    box.schema.user.grant('jepsen', 'create,read,write,execute,drop,alter,replication', 'universe')
    box.schema.user.grant('jepsen', 'read,write', 'space', '_index')
    box.schema.user.grant('jepsen', 'write', 'space', '_schema')
    box.schema.user.grant('jepsen', 'write', 'space', '_space')

--[[ Function implements a CAS (Compare And Set) operation, which takes a key,
old value, and new value and sets the key to the new value if and only if the
old value matches what's currently there, and returns a detailed response
map. If the CaS fails, it returns false.
Example: SELECT _CAS(1, 3, 4, 'JEPSEN')
]]
box.schema.func.create('_CAS',
   {language = 'LUA',
    returns = 'boolean',
    body = [[function(id, old_value, new_value, table)
             local rc = false
             box.begin()
             local tuple = box.space[table]:get{id}
             if tuple then
                 if tuple[2] == old_value then
                     box.space[table]:update({id}, {{'=', 2, new_value}})
                     rc = true
                 end
             end
             box.commit()

             return rc
             end]],
    is_sandboxed = false,
    param_list = {'integer', 'integer', 'integer', 'string'},
    exports = {'LUA', 'SQL'},
    is_deterministic = true})

--[[ Function implements a UPSERT operation, which takes a key and value
and sets the key to the value if key exists or insert new key with that value.
Example: SELECT _UPSERT(1, 3, 4, 'JEPSEN')
]]
box.schema.func.create('_UPSERT',
   {language = 'LUA',
    returns = 'boolean',
    body = [[function(id, value, table)
             box.space[table]:upsert({id, value}, {{'=', 2, value}})
             return true
             end]],
    is_sandboxed = false,
    param_list = {'integer', 'integer', 'string'},
    exports = {'LUA', 'SQL'},
    is_deterministic = true})

--[[ Function returns IP address of a node where current leader
of synchronous cluster with enabled Raft consensus protocol is started.
Returns nil when Raft is disabled. Example: SELECT _LEADER()
]]
box.schema.func.create('_LEADER',
   {language = 'LUA',
    returns = 'string',
    body = [[function()
             local leader_id = box.info.election.leader
             if leader_id == 0 or leader_id == nil then
               return nil
             end
             local leader_upstream = box.info.replication[leader_id].upstream
             if leader_upstream == nil then
               return string.match(box.info.listen, '(.+):[0-9]+')
             end
             local leader_ip_address = string.match(leader_upstream.peer, '[A-z]+@(.+):[0-9]+')

             return leader_ip_address
             end]],
    is_sandboxed = false,
    param_list = {},
    exports = {'LUA', 'SQL'},
    is_deterministic = true})
end

box.once('jepsen', bootstrap)

require('console').start()
