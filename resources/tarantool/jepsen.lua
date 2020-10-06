single_mode = %TARANTOOL_SINGLE_MODE%

if single_mode then
box.cfg {
    listen = 3301;
    log_level = 6;
    log_nonblock = false;
    too_long_threshold = 0.5;
    custom_proc_title = 'jepsen';
    memtx_memory = 1024*1024*1024;
}
else
box.cfg {
    listen = 3301;
    replication = { %TARANTOOL_REPLICATION% };
    read_only = %TARANTOOL_IS_READ_ONLY%;
    replication_synchro_quorum = 2;
    replication_synchro_timeout = 0.2;
    replication_timeout = 2;
    election_is_enabled = true;
    election_is_candidate = true;
    election_timeout = 0.3;
    log_level = 6;
    log_nonblock = false;
    too_long_threshold = 0.5;
    custom_proc_title = 'jepsen';
    memtx_memory = 1024*1024*1024,
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
end

box.once('jepsen', bootstrap)

require('console').start()
