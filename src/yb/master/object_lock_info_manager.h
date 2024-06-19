// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

#pragma once

#include <string>
#include <unordered_map>
#include <boost/functional/hash.hpp>

#include "yb/master/master_fwd.h"

namespace yb::rpc {
class RpcContext;
}
namespace yb::tserver {
class AcquireObjectLockRequestPB;
class AcquireObjectLockResponsePB;
class ReleaseObjectLockRequestPB;
class ReleaseObjectLockResponsePB;
class DdlLockEntriesPB;
}  // namespace yb::tserver

namespace yb::master {

struct LeaderEpoch;
class ObjectLockInfo;

class ObjectLockInfoManager {
 public:
  ObjectLockInfoManager(Master* master, CatalogManager* catalog_manager);
  virtual ~ObjectLockInfoManager();

  void LockObject(
      LeaderEpoch epoch, const tserver::AcquireObjectLockRequestPB* req,
      tserver::AcquireObjectLockResponsePB* resp, rpc::RpcContext rpc);

  void UnlockObject(
      LeaderEpoch epoch, const tserver::ReleaseObjectLockRequestPB* req,
      tserver::ReleaseObjectLockResponsePB* resp, rpc::RpcContext rpc);

  void ExportObjectLockInfo(tserver::DdlLockEntriesPB* resp);
  bool InsertOrAssign(const std::string& tserver_uuid, std::shared_ptr<ObjectLockInfo> info);
  void Clear();

 private:
  template <class Req, class Resp>
  friend class UpdateAllTServers;
  class Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace yb::master
