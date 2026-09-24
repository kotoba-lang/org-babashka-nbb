import { $APP, shadow$provide } from "./nbb_core.js";
const shadow_esm_import = function(x) { return import(x) };
var $Wa;$APP.Y5=function(a,b){const c=Array.prototype.slice.call(arguments),d=c.shift();if(typeof d=="undefined")throw Error("[goog.string.format] Template required");return d.replace(/%([0\- \+]*)(\d+)?(\.(\d+))?([%sfdiu])/g,function(e,f,g,m,r,t,v,z){if(t=="%")return"%";const C=c.shift();if(typeof C=="undefined")throw Error("[goog.string.format] Not enough arguments");arguments[0]=C;return $APP.Y5.$d[t].apply(null,arguments)})};$Wa=new $APP.l(null,"format","format",333606761,null);/*

 Copyright The Closure Library Authors.
 SPDX-License-Identifier: Apache-2.0
*/
$APP.Y5.$d={};$APP.Y5.$d.s=function(a,b,c){return isNaN(c)||c==""||a.length>=Number(c)?a:a=b.indexOf("-",0)>-1?a+(0,$APP.yB)(" ",Number(c)-a.length):(0,$APP.yB)(" ",Number(c)-a.length)+a};
$APP.Y5.$d.f=function(a,b,c,d,e){d=a.toString();isNaN(e)||e==""||(d=parseFloat(a).toFixed(e));let f;f=Number(a)<0?"-":b.indexOf("+")>=0?"+":b.indexOf(" ")>=0?" ":"";Number(a)>=0&&(d=f+d);if(isNaN(c)||d.length>=Number(c))return d;d=isNaN(e)?Math.abs(Number(a)).toString():Math.abs(Number(a)).toFixed(e);a=Number(c)-d.length-f.length;b.indexOf("-",0)>=0?d=f+d+(0,$APP.yB)(" ",a):(b=b.indexOf("0",0)>=0?"0":" ",d=f+(0,$APP.yB)(b,a)+d);return d};
$APP.Y5.$d.d=function(a,b,c,d,e,f,g,m){return $APP.Y5.$d.f(parseInt(a,10),b,c,d,0,f,g,m)};$APP.Y5.$d.i=$APP.Y5.$d.d;$APP.Y5.$d.u=$APP.Y5.$d.d;$APP.fY.j($APP.$J,null);$APP.Hz(new $APP.F(null,2,[$APP.Fy,new $APP.F(null,1,[$APP.QG,{format:$APP.Y5}],null),$APP.xt,new $APP.F(null,1,[$APP.QG,new $APP.F(null,1,[$Wa,$APP.Y5],null)],null)],null));