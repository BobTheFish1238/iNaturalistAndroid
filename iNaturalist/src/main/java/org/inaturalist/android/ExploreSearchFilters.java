package org.inaturalist.android;

import android.text.format.DateFormat;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.tinylog.Logger;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Represents explore screen filters / search params */
public class ExploreSearchFilters implements Serializable {
    public static final String QUALITY_GRADE_CASUAL = "casual";
    public static final String QUALITY_GRADE_NEEDS_ID = "needs_id";
    public static final String QUALITY_GRADE_RESEARCH = "research";
    private static final String TAG = "ExploreSearchFilters";

    public boolean isCurrentLocation = false;

    public transient JSONObject taxon;
    public transient JSONObject place;
    public transient JSONObject project;
    public transient JSONObject user;
    public transient JSONObject withoutIdentUser; // Exclude observations identified by this user
    public transient LatLngBounds mapBounds;
    public Set<String> iconicTaxa = new HashSet<>();
    public Set<String> qualityGrade = new HashSet<>();

    public Set<Integer> observedOnMonths = new HashSet<>(); // List of months
    public Date observedOn; // Exact date
    // Between min and max dates
    public Date observedOnMinDate;
    public Date observedOnMaxDate;

    public static final int DATE_TYPE_ANY = 0;
    public static final int DATE_TYPE_EXACT_DATE = 1;
    public static final int DATE_TYPE_MIN_MAX_DATE = 2;
    public static final int DATE_TYPE_MONTHS = 3;

    public static final String ORDER_DESCENDING = "desc";
    public static final String ORDER_ASCENDING = "asc";

    public static final String ORDER_BY_CREATED_AT = "created_at";
    public static final String ORDER_BY_OBSERVED_ON = "observed_on";
    public static final String ORDER_BY_UPDATED_AT = "updated_at";
    public static final String ORDER_BY_VOTES = "votes";
    public static final String ORDER_BY_RANDOM= "random";

    public int dateFilterType = DATE_TYPE_ANY;

    private String placeJson;
    private String taxonJson;
    private String projectJson;
    private String userJson;
    private String withoutIdentUserJson;

    public Integer annotationNameId;
    public String annotationName;
    public Integer annotationValueId;
    public String annotationValue;

    public String order; // Ascending or descending
    public String orderBy; // Created at, observed on, ...

    public boolean hasPhotos = false;
    public boolean hasSounds = false;

    public Integer hideObservationsUserId = null;

    // Geoprivacy filters - one of the GEOPRIVACY_* values, or null for "any"
    public static final String GEOPRIVACY_OPEN = "open";
    public static final String GEOPRIVACY_OBSCURED = "obscured";
    public static final String GEOPRIVACY_PRIVATE = "private";
    public static final String GEOPRIVACY_OBSCURED_PRIVATE = "obscured_private";

    public String geoprivacy = null; // Observation geoprivacy
    public String taxonGeoprivacy = null; // Geoprivacy applied because of the taxon's conservation status

    // Coordinate precision limit, in meters (null = no limit). Observations with a blank accuracy
    // are treated as having exact coordinates, so they're always included
    public Integer maxPositionalAccuracy = null;

    // Only observations that were created with this OAuth application (e.g. 2 = iNaturalist Android app)
    public Integer oauthApplicationId = null;

    // Known OAuth applications (must match the explore_oauth_app_values array)
    public static final int OAUTH_APP_INATURALIST_ANDROID = 2;
    public static final int OAUTH_APP_INATURALIST_IOS = 3;
    public static final int OAUTH_APP_SEEK = 333;
    public static final int OAUTH_APP_INATURALIST_NEXT = 843;

    // Any other search params that the user manually added (by editing the URL in the filters screen),
    // and that don't have a dedicated filter in the UI. Key = param name, value = param value
    public LinkedHashMap<String, String> extraParams = new LinkedHashMap<>();

    public ExploreSearchFilters() {
        resetToDefault();
    }

    // Reset search filters to default
    public void resetToDefault() {
        qualityGrade = new HashSet<>();
        qualityGrade.add(QUALITY_GRADE_RESEARCH);
        qualityGrade.add(QUALITY_GRADE_NEEDS_ID);

        user = null;
        project = null;
        iconicTaxa = new HashSet<>();

        observedOn = null;
        observedOnMinDate = null;
        observedOnMaxDate = null;
        observedOnMonths = new HashSet<>();

        dateFilterType = DATE_TYPE_ANY;

        annotationNameId = null;
        annotationValueId = null;
        annotationName = null;
        annotationValue = null;

        order = ORDER_DESCENDING;
        orderBy = ORDER_BY_CREATED_AT;

        hideObservationsUserId = null;

        withoutIdentUser = null;
        geoprivacy = null;
        taxonGeoprivacy = null;
        maxPositionalAccuracy = null;
        oauthApplicationId = null;
        extraParams = new LinkedHashMap<>();
    }

    public boolean isDirty() {
        return ((!iconicTaxa.isEmpty()) || (project != null) || (user != null) ||
                (qualityGrade.contains(ExploreSearchFilters.QUALITY_GRADE_CASUAL)) ||
                (!qualityGrade.contains(ExploreSearchFilters.QUALITY_GRADE_NEEDS_ID)) ||
                (!qualityGrade.contains(ExploreSearchFilters.QUALITY_GRADE_RESEARCH)) ||
                (dateFilterType != ExploreSearchFilters.DATE_TYPE_ANY) ||
                (observedOn != null) || (observedOnMinDate != null) ||
                (observedOnMaxDate != null) || (!observedOnMonths.isEmpty()) ||
                (annotationNameId != null) || (annotationValueId != null)) ||
                (!order.equals(ORDER_DESCENDING)) || (!orderBy.equals(ORDER_BY_CREATED_AT)) ||
                (hasPhotos) || (hasSounds) ||
                (hideObservationsUserId != null) ||
                (withoutIdentUser != null) ||
                (geoprivacy != null) || (taxonGeoprivacy != null) ||
                (maxPositionalAccuracy != null) || (oauthApplicationId != null) ||
                (extraParams != null && !extraParams.isEmpty());
    }

    private void writeObject(ObjectOutputStream oos) throws IOException {
        taxonJson = taxon != null ? taxon.toString() : null;
        placeJson = place != null ? place.toString() : null;
        projectJson = project != null ? project.toString() : null;
        userJson = user != null ? user.toString() : null;
        withoutIdentUserJson = withoutIdentUser != null ? withoutIdentUser.toString() : null;

        oos.defaultWriteObject();

        if (mapBounds != null) {
            oos.writeDouble(mapBounds.southwest.latitude);
            oos.writeDouble(mapBounds.southwest.longitude);
            oos.writeDouble(mapBounds.northeast.latitude);
            oos.writeDouble(mapBounds.northeast.longitude);
        }
    }

    private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
        ois.defaultReadObject();

        try {
            user = userJson != null ? new JSONObject(userJson) : null;
            withoutIdentUser = withoutIdentUserJson != null ? new JSONObject(withoutIdentUserJson) : null;
            project = projectJson != null ? new JSONObject(projectJson) : null;
            place = placeJson != null ? new JSONObject(placeJson) : null;
            taxon = taxonJson != null ? new JSONObject(taxonJson) : null;
        } catch (JSONException e) {
            Logger.tag(TAG).error(e);
        }

        if (ois.available() > 0) {
            mapBounds = new LatLngBounds(new LatLng(ois.readDouble(), ois.readDouble()), new LatLng(ois.readDouble(), ois.readDouble()));
        }
    }

    // Returns a string representation of the search filters, for use a URL search query (e.g.
    // taxon_id=1234&place_id=567&iconic_taxa=Aves )
    public String toUrlQueryString() {
        StringBuilder url = new StringBuilder();

        if (!iconicTaxa.isEmpty()) {
            url.append("&iconic_taxa=" + StringUtils.join(iconicTaxa, ",").toLowerCase());
        }

        if (taxon != null) {
            url.append("&taxon_id=" + taxon.optInt("id"));
        }

        if (place != null) {
            url.append("&place_id=" + place.optInt("id"));
        }

        if (project != null) {
            url.append("&project_id=" + project.optInt("id"));
        }

        if (user != null) {
            if (user.has("login")) {
                url.append("&user_id=" + user.optString("login"));
            } else {
                url.append("&user_id=" + user.optInt("id"));
            }
        }

        if (hideObservationsUserId != null) {
            url.append("&not_user_id=" + hideObservationsUserId);
        }

        if (withoutIdentUser != null) {
            url.append("&without_ident_user_id=" + withoutIdentUser.optInt("id"));
        }

        if (!qualityGrade.isEmpty()) {
            url.append("&quality_grade=" + StringUtils.join(qualityGrade, ","));
        }

        if (mapBounds != null) {
            url.append(String.format("&swlng=%s&swlat=%s&nelng=%s&nelat=%s",
                    mapBounds.southwest.longitude, mapBounds.southwest.latitude,
                    mapBounds.northeast.longitude, mapBounds.northeast.latitude
            ));
        }

        if ((dateFilterType == DATE_TYPE_MONTHS) && (!observedOnMonths.isEmpty())) {
            url.append("&month=" + StringUtils.join(observedOnMonths, ","));
        } else if ((dateFilterType == DATE_TYPE_EXACT_DATE) && (observedOn != null)) {
            url.append("&observed_on=" + formatDate(observedOn));
        } else if (dateFilterType == DATE_TYPE_MIN_MAX_DATE) {
            if (observedOnMinDate != null) url.append("&d1=" + formatDate(observedOnMinDate));
            if (observedOnMaxDate != null) url.append("&d2=" + formatDate(observedOnMaxDate));
        }

        if (annotationNameId != null) {
            url.append("&term_id=" + annotationNameId);

            if (annotationValueId != null) {
                url.append("&term_value_id=" + annotationValueId);
            }
        }

        if (!StringUtils.isEmpty(geoprivacy)) {
            url.append("&geoprivacy=" + geoprivacy);
        }
        if (!StringUtils.isEmpty(taxonGeoprivacy)) {
            url.append("&taxon_geoprivacy=" + taxonGeoprivacy);
        }

        if (maxPositionalAccuracy != null) {
            // acc_below_or_unknown: blank accuracy = exact coordinates, so those are included
            url.append("&acc_below_or_unknown=" + maxPositionalAccuracy);
        }

        if (oauthApplicationId != null) {
            url.append("&oauth_application_id=" + oauthApplicationId);
        }

        if (hasPhotos) {
            url.append("&photos=true");
        }
        if (hasSounds) {
            url.append("&sounds=true");
        }

        url.append("&order_by=" + orderBy);
        url.append("&order=" + order);

        // Params that were manually added by the user (skip ones already generated above)
        if (extraParams != null) {
            for (Map.Entry<String, String> param : extraParams.entrySet()) {
                if (url.indexOf("&" + param.getKey() + "=") >= 0) continue;
                url.append("&" + encodeParam(param.getKey()) + "=" + encodeParam(param.getValue()));
            }
        }

        if (url.length() == 0) return url.toString();

        return url.substring(1);
    }

    private static String encodeParam(String value) {
        try {
            // Keep commas readable (e.g. quality_grade=research,needs_id)
            return URLEncoder.encode(value, "UTF-8").replace("%2C", ",");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String decodeParam(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Date parseDate(String value) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(value.trim());
        } catch (ParseException e) {
            return null;
        }
    }

    /**
     * Updates these filters from a (possibly manually edited) URL or query string, e.g.
     * "https://www.inaturalist.org/observations?quality_grade=research&amp;geoprivacy=obscured".
     * This is the reverse of toUrlQueryString - the URL is the source of truth: filters whose param
     * is missing from it are cleared. Params that don't have a dedicated filter (or whose value
     * isn't understood) are kept as-is in extraParams, so they're still sent with the search.
     */
    public void applyUrlQueryString(String urlOrQuery) {
        String query = urlOrQuery == null ? "" : urlOrQuery.trim();

        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        int question = query.indexOf('?');
        if (question >= 0) query = query.substring(question + 1);
        else if (query.startsWith("http://") || query.startsWith("https://")) query = ""; // URL without params

        // Parse into an ordered map of param name -> value (last value wins)
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.trim().isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = decodeParam(eq >= 0 ? pair.substring(0, eq) : pair).trim();
            String value = decodeParam(eq >= 0 ? pair.substring(eq + 1) : "").trim();
            if (key.isEmpty()) continue;
            params.put(key, value);
        }

        // Taxon / place / project / user (only replace the stored object when the id actually changed,
        // to keep its details - name, icon, etc.)
        String value;
        Integer id;

        value = params.get("taxon_id");
        id = value != null ? parseInt(value) : null;
        if (id != null) {
            params.remove("taxon_id");
            if ((taxon == null) || (taxon.optInt("id") != id)) taxon = newIdObject(id);
        } else {
            taxon = null;
        }
        if (params.containsKey("iconic_taxa")) {
            iconicTaxaFromParam(params.remove("iconic_taxa"));
        } else {
            iconicTaxa = new HashSet<>();
        }

        value = params.get("place_id");
        id = value != null ? parseInt(value) : null;
        if (id != null) {
            params.remove("place_id");
            if ((place == null) || (place.optInt("id") != id)) place = newIdObject(id);
        } else {
            place = null;
        }

        value = params.get("project_id");
        id = value != null ? parseInt(value) : null;
        if (id != null) {
            params.remove("project_id");
            if ((project == null) || (project.optInt("id") != id)) project = newIdObject(id);
        } else {
            project = null;
        }

        value = params.get("user_id");
        if ((value != null) && (!value.isEmpty()) && (!value.contains(","))) {
            params.remove("user_id");
            String currentUser = user == null ? null : (user.has("login") ? user.optString("login") : String.valueOf(user.optInt("id")));
            if (!value.equals(currentUser)) {
                user = new JSONObject();
                try {
                    user.put("login", value); // Works for both a login and a numeric user ID
                    Integer userId = parseInt(value);
                    if (userId != null) user.put("id", userId);
                } catch (JSONException e) {
                    Logger.tag(TAG).error(e);
                }
            }
        } else {
            user = null;
        }

        value = params.get("without_ident_user_id");
        id = value != null ? parseInt(value) : null;
        if (id != null) {
            params.remove("without_ident_user_id");
            if ((withoutIdentUser == null) || (withoutIdentUser.optInt("id") != id)) {
                withoutIdentUser = newIdObject(id);
                try { withoutIdentUser.put("login", String.valueOf(id)); } catch (JSONException e) { Logger.tag(TAG).error(e); }
            }
        } else {
            withoutIdentUser = null;
        }

        value = params.get("not_user_id");
        id = value != null ? parseInt(value) : null;
        if (id != null) {
            params.remove("not_user_id");
            hideObservationsUserId = id;
        } else {
            hideObservationsUserId = null;
        }

        // Quality grade (absent = all grades)
        value = params.get("quality_grade");
        qualityGrade = new HashSet<>();
        if (value != null) {
            boolean allValid = true;
            Set<String> grades = new HashSet<>();
            for (String grade : value.split(",")) {
                grade = grade.trim().toLowerCase(Locale.ENGLISH);
                if (grade.isEmpty()) continue;
                if (grade.equals(QUALITY_GRADE_CASUAL) || grade.equals(QUALITY_GRADE_NEEDS_ID) || grade.equals(QUALITY_GRADE_RESEARCH)) {
                    grades.add(grade);
                } else {
                    allValid = false;
                }
            }
            if (allValid) {
                qualityGrade = grades;
                params.remove("quality_grade");
            }
        }

        // Map bounds (only when all 4 are there)
        if (params.containsKey("swlng") && params.containsKey("swlat") && params.containsKey("nelng") && params.containsKey("nelat")) {
            try {
                double swlng = Double.parseDouble(params.get("swlng"));
                double swlat = Double.parseDouble(params.get("swlat"));
                double nelng = Double.parseDouble(params.get("nelng"));
                double nelat = Double.parseDouble(params.get("nelat"));

                if ((mapBounds == null) ||
                        (mapBounds.southwest.longitude != swlng) || (mapBounds.southwest.latitude != swlat) ||
                        (mapBounds.northeast.longitude != nelng) || (mapBounds.northeast.latitude != nelat)) {
                    mapBounds = new LatLngBounds(new LatLng(swlat, swlng), new LatLng(nelat, nelng));
                }
                params.remove("swlng");
                params.remove("swlat");
                params.remove("nelng");
                params.remove("nelat");
            } catch (Exception e) {
                // Invalid bounds - keep them as extra params
            }
        } else if (!params.containsKey("swlng") && !params.containsKey("swlat") && !params.containsKey("nelng") && !params.containsKey("nelat")) {
            mapBounds = null;
        }

        // Dates
        observedOn = null;
        observedOnMinDate = null;
        observedOnMaxDate = null;
        observedOnMonths = new HashSet<>();
        dateFilterType = DATE_TYPE_ANY;

        value = params.get("month");
        if ((value != null) && (dateFilterType == DATE_TYPE_ANY)) {
            Set<Integer> months = new HashSet<>();
            boolean valid = true;
            for (String month : value.split(",")) {
                Integer m = parseInt(month);
                if ((m == null) || (m < 1) || (m > 12)) { valid = false; break; }
                months.add(m);
            }
            if (valid && !months.isEmpty()) {
                observedOnMonths = months;
                dateFilterType = DATE_TYPE_MONTHS;
                params.remove("month");
            }
        }
        value = params.get("observed_on");
        if ((value != null) && (dateFilterType == DATE_TYPE_ANY)) {
            Date date = parseDate(value);
            if (date != null) {
                observedOn = date;
                dateFilterType = DATE_TYPE_EXACT_DATE;
                params.remove("observed_on");
            }
        }
        if ((dateFilterType == DATE_TYPE_ANY) && (params.containsKey("d1") || params.containsKey("d2"))) {
            Date d1 = params.containsKey("d1") ? parseDate(params.get("d1")) : null;
            Date d2 = params.containsKey("d2") ? parseDate(params.get("d2")) : null;
            if ((params.containsKey("d1") && d1 == null) || (params.containsKey("d2") && d2 == null)) {
                // Not a valid date - keep as extra params
            } else {
                observedOnMinDate = d1;
                observedOnMaxDate = d2;
                dateFilterType = DATE_TYPE_MIN_MAX_DATE;
                params.remove("d1");
                params.remove("d2");
            }
        }

        // Annotations
        value = params.get("term_id");
        id = value != null ? parseInt(value) : null;
        if (id != null) {
            params.remove("term_id");
            if (!id.equals(annotationNameId)) {
                annotationNameId = id;
                annotationName = null; // The screen will fill the label back in
                annotationValueId = null;
                annotationValue = null;
            }

            value = params.get("term_value_id");
            Integer valueId = value != null ? parseInt(value) : null;
            if (valueId != null) {
                params.remove("term_value_id");
                if (!valueId.equals(annotationValueId)) {
                    annotationValueId = valueId;
                    annotationValue = null;
                }
            } else {
                annotationValueId = null;
                annotationValue = null;
            }
        } else {
            annotationNameId = null;
            annotationName = null;
            annotationValueId = null;
            annotationValue = null;
        }

        // Booleans
        hasPhotos = "true".equalsIgnoreCase(params.get("photos"));
        if (params.containsKey("photos") && (hasPhotos || "false".equalsIgnoreCase(params.get("photos")))) params.remove("photos");
        hasSounds = "true".equalsIgnoreCase(params.get("sounds"));
        if (params.containsKey("sounds") && (hasSounds || "false".equalsIgnoreCase(params.get("sounds")))) params.remove("sounds");

        // Geoprivacy
        geoprivacy = null;
        value = params.get("geoprivacy");
        if (value != null) {
            if (isGeoprivacyValue(value)) {
                geoprivacy = value;
                params.remove("geoprivacy");
            } else if (value.isEmpty()) {
                params.remove("geoprivacy");
            }
        }
        taxonGeoprivacy = null;
        value = params.get("taxon_geoprivacy");
        if (value != null) {
            if (isGeoprivacyValue(value)) {
                taxonGeoprivacy = value;
                params.remove("taxon_geoprivacy");
            } else if (value.isEmpty()) {
                params.remove("taxon_geoprivacy");
            }
        }

        // Precision limit / OAuth application
        maxPositionalAccuracy = null;
        value = params.get("acc_below_or_unknown");
        id = value != null ? parseInt(value) : null;
        if (id != null) {
            maxPositionalAccuracy = id;
            params.remove("acc_below_or_unknown");
        }

        oauthApplicationId = null;
        value = params.get("oauth_application_id");
        id = value != null ? parseInt(value) : null;
        if ((id != null) && (id == OAUTH_APP_INATURALIST_ANDROID || id == OAUTH_APP_INATURALIST_IOS ||
                id == OAUTH_APP_SEEK || id == OAUTH_APP_INATURALIST_NEXT)) {
            oauthApplicationId = id;
            params.remove("oauth_application_id");
        } // else: some other app id - kept as an extra param (the dropdown only knows the apps above)

        // Sorting (absent = defaults)
        order = ORDER_DESCENDING;
        orderBy = ORDER_BY_CREATED_AT;
        value = params.get("order");
        if ((value != null) && (value.equals(ORDER_ASCENDING) || value.equals(ORDER_DESCENDING))) {
            order = value;
            params.remove("order");
        }
        value = params.get("order_by");
        if ((value != null) && (value.equals(ORDER_BY_CREATED_AT) || value.equals(ORDER_BY_OBSERVED_ON) ||
                value.equals(ORDER_BY_UPDATED_AT) || value.equals(ORDER_BY_VOTES) || value.equals(ORDER_BY_RANDOM))) {
            orderBy = value;
            params.remove("order_by");
        }

        // Whatever is left is a param we don't have a filter for - keep it
        extraParams = new LinkedHashMap<>(params);
    }

    private static boolean isGeoprivacyValue(String value) {
        return value.equals(GEOPRIVACY_OPEN) || value.equals(GEOPRIVACY_OBSCURED) ||
                value.equals(GEOPRIVACY_PRIVATE) || value.equals(GEOPRIVACY_OBSCURED_PRIVATE);
    }

    private static JSONObject newIdObject(int id) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
        } catch (JSONException e) {
            Logger.tag(TAG).error(e);
        }
        return obj;
    }

    // Returns true if the param was present and fully understood (and sets iconicTaxa accordingly)
    private boolean iconicTaxaFromParam(String value) {
        if (value == null) return false;

        Set<String> result = new HashSet<>();
        for (String name : value.split(",")) {
            name = name.trim();
            if (name.isEmpty()) continue;
            result.add(StringUtils.capitalize(name.toLowerCase(Locale.ENGLISH)));
        }
        iconicTaxa = result;
        return true;
    }

    public String formatDate(Date date) {
        return DateFormat.format("yyyy-MM-dd", date).toString();
    }
}
