import { $APP, shadow$provide } from "./nbb_core.js";
import "./nbb_api.js";
import "./nbb_impl_main.js";
const shadow_esm_import = function(x) { return import(x) };
import*as esm_import$node_process from"node:process";if($APP.k(esm_import$node_process.on))esm_import$node_process.on("unhandledRejection",function(a){console.error($APP.Ik(a));return process.exitCode=1});$APP.z2();