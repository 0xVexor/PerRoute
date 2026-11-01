package app.perroute.platform.macos;

import app.perroute.gateway.AppGateway;
import app.perroute.model.ApplicationIdentity;
import app.perroute.platform.macos.routing.MacTunStartRequest;
import app.perroute.util.Json;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Disposable catalog/file fixtures only. No helper, process launch or host routing changes. */
public final class MacApplicationFamilyTests {
    private static int checks;
    private static void check(boolean condition, String reason) { checks++; if (!condition) throw new AssertionError(reason); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void fails(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected application family refusal");
    }
    private static Map<String,Object> fixture(Path bundle, String name) throws IOException {
        Path executable = bundle.resolve("Contents/MacOS/" + name);
        Files.createDirectories(executable.getParent()); Files.writeString(executable,"fixture, never executed");
        if (!executable.toFile().setExecutable(true,true)) throw new IOException("Cannot mark fixture executable");
        return Map.of("name",name,"bundlePath",bundle.toString(),"executablePath",executable.toString(),
                "bundleIdentifier","fixture.same.identifier","signatureValid",false,"signingIdentifier","","teamIdentifier","");
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("perroute-families-").toRealPath();
        try {
            Path bundle=root.resolve("Browser فارسی.app");
            var main=fixture(bundle,"Browser");
            var helper=fixture(bundle.resolve("Contents/Frameworks/1/Network.app"),"Network");
            var oldHelper=fixture(bundle.resolve("Contents/Frameworks/0/Network.app"),"Network");
            var other=fixture(root.resolve("Browser فارسی.app-other.app"),"Network");
            var rows=new ArrayList<>(List.of(main,helper,oldHelper,other));
            var warnings=new ArrayList<String>();
            var reads=new int[1];
            var catalog=new MacApplicationCatalog(op -> { reads[0]++; return Json.stringify(Map.of("version",1,"applications",rows,"warnings",warnings)); });
            var families=new MacApplicationFamilies(catalog);
            String path=(String)main.get("executablePath"), exact=ApplicationIdentity.macExecutable(path), family=ApplicationIdentity.macAppFamily(path);
            var descriptors=MacApplicationFamilies.descriptors(catalog.installed());
            check(descriptors.size()==5,"Main/helpers retained, one explicit family offered");
            check(descriptors.stream().filter(app -> ApplicationIdentity.isMacFamily(app.path())).count()==1,"Nested helpers cannot independently broaden into a family");
            check(descriptors.stream().anyMatch(app -> app.path().equals(family) && app.name().endsWith("(including helpers)")),"Family scope is explicit in picker");
            check(families.validateSelection(family).equals(family),"Observed canonical outer family can be selected");
            var route=new AppGateway.Route(family,0,18001,18002);
            reads[0]=0; var bindings=families.bindings(List.of(route));
            check(reads[0]==1,"One complete inventory per family expansion");
            check(bindings.size()==3,"Main and both versioned helper generations included");
            check(bindings.stream().allMatch(binding -> binding.socksPort()==18001),"One family shares one gateway/counter/limit listener");
            check(bindings.stream().noneMatch(binding -> binding.executablePath().equals(other.get("executablePath"))),"Path-prefix lookalike remains excluded");
            check(bindings.stream().map(binding -> binding.executablePath()).distinct().count()==3,"Each helper retains exact distinct executable authority");
            var wire=new MacTunStartRequest(bindings,2,List.of(18002));
            check(((List<?>)wire.values().get("bindings")).size()==3,"Existing helper wire supports three paths and one upstream");
            check(families.bindings(List.of(new AppGateway.Route(exact,0,18001,18002))).size()==1,"Saved main-only selection does not gain helper authority");
            fails(() -> families.bindings(List.of(route,new AppGateway.Route(exact,1,18003,18004))));
            fails(() -> families.bindings(List.of(route,new AppGateway.Route(ApplicationIdentity.macExecutable((String)helper.get("executablePath")),1,18003,18004))));
            fails(() -> families.validateSelection(ApplicationIdentity.macAppFamily((String)helper.get("executablePath"))));
            fails(() -> families.validateSelection(ApplicationIdentity.macAppFamily(root.resolve("Missing.app/Contents/MacOS/Missing").toString())));
            warnings.add("Partial inventory");
            fails(() -> families.bindings(List.of(route)));
            fails(() -> families.validateSelection(family));
            fails(() -> MacApplicationFamilies.descriptors(catalog.installed()));
            warnings.clear();
            Path helperExecutable=Path.of((String)helper.get("executablePath"));
            Files.delete(helperExecutable); Files.createSymbolicLink(helperExecutable,Path.of((String)other.get("executablePath")));
            fails(() -> families.bindings(List.of(route)));
            Files.delete(helperExecutable); Files.writeString(helperExecutable,"fixture"); helperExecutable.toFile().setExecutable(true,true);
            rows.remove(oldHelper); Files.delete(Path.of((String)helper.get("executablePath")));
            fails(() -> families.bindings(List.of(route)));
            rows.remove(helper);
            check(families.bindings(List.of(route)).size()==1,"Explicit family can remain valid when an app update removes all helpers");
            for (int i=0;i<256;i++) rows.add(fixture(bundle.resolve("Contents/Helpers/H"+i+".app"),"H"+i));
            fails(() -> families.bindings(List.of(route)));
            check(!ApplicationIdentity.same(exact,family),"Exact and family identities remain distinct");
            System.out.println("macOS application families: " + checks + " checks passed; disposable fixtures, no capture");
        } finally {
            try (var paths=Files.walk(root)) { for (var path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
