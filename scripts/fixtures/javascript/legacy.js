const item={vod_id:"1",vod_name:"Fixture"},listing={list:[item]};var __spider = {
 init(ext){this.ext = ext;},
 home(){if(this.ext !== "fixture") throw Error("init"); return {class:[{type_id:"1",type_name:"Videos"}],...listing};},
 category(id,page,filter,ext){if(typeof ext !== "object") throw Error("filters");return listing;},
 search(){const response = req("./config.json"); if(response.code !== 200) throw Error("http");return listing;},
 detailContent(ids){if(!Array.isArray(ids)||ids[0] !== "1") throw Error("ids");return {list:[{...item,vod_play_from:"video",vod_play_url:"Episode$http://10.0.2.2:8767/video.mp4"}]};},
 play(flag,id){return {parse:0,url:id};}
};
